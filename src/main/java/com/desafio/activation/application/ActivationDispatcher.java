package com.desafio.activation.application;

import com.desafio.activation.domain.ActivationRequest;
import com.desafio.activation.domain.ActivationStatus;
import com.desafio.activation.infrastructure.persistence.ActivationRequestRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Envia uma solicitação ao provedor e registra o resultado.
 *
 * <p>Três passos, de propósito <b>sem</b> transação aberta durante a chamada HTTP:
 * <ol>
 *   <li>transação curta: PENDING → PROCESSING ("reivindica" a solicitação; lock otimista
 *       garante que só uma instância/thread vence);</li>
 *   <li>chamada ao provedor (fora de transação: não segura conexão do pool do banco);</li>
 *   <li>transação curta: aplica o resultado (COMPLETED, FAILED ou PENDING para nova tentativa).</li>
 * </ol>
 * Se a aplicação cair entre os passos, a solicitação fica em PROCESSING e o
 * {@link #releaseIfStuck reconciliador} a devolve para a fila depois do lease.
 */
@Component
public class ActivationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ActivationDispatcher.class);

    private final ActivationRequestRepository repository;
    private final ActivationProvider provider;
    private final TransactionTemplate transactionTemplate;
    private final RetryPolicy retryPolicy;
    private final TimeSource time;
    private final MeterRegistry meterRegistry;

    public ActivationDispatcher(ActivationRequestRepository repository,
                                ActivationProvider provider,
                                TransactionTemplate transactionTemplate,
                                RetryPolicy retryPolicy,
                                TimeSource time,
                                MeterRegistry meterRegistry) {
        this.repository = repository;
        this.provider = provider;
        this.transactionTemplate = transactionTemplate;
        this.retryPolicy = retryPolicy;
        this.time = time;
        this.meterRegistry = meterRegistry;
    }

    public void process(UUID requestId) {
        try {
            claim(requestId).ifPresent(claim -> {
                ProviderResult result = callProvider(claim.command());
                record(requestId, claim.version(), result);
            });
        } catch (RuntimeException e) {
            // Nunca derruba o worker. A solicitação fica em PROCESSING e o reconciliador a recupera.
            log.error("Falha inesperada processando solicitação id={}", requestId, e);
        }
    }

    /** Devolve para a fila (ou falha) uma solicitação em PROCESSING além do lease. */
    public void releaseIfStuck(UUID requestId, Instant threshold) {
        try {
            transactionTemplate.executeWithoutResult(status -> repository.findById(requestId)
                    .filter(r -> r.getStatus() == ActivationStatus.PROCESSING)
                    .filter(r -> r.getUpdatedAt().isBefore(threshold))
                    .ifPresent(r -> {
                        var now = time.now();
                        if (retryPolicy.canRetry(r.getAttempts())) {
                            r.retryLater("LEASE_EXPIRED", now, now);
                        } else {
                            r.fail("MAX_ATTEMPTS_EXCEEDED: LEASE_EXPIRED", now);
                        }
                        repository.saveAndFlush(r);
                        log.warn("Solicitação presa em PROCESSING liberada id={} attempts={} status={}",
                                r.getId(), r.getAttempts(), r.getStatus());
                    }));
        } catch (OptimisticLockingFailureException e) {
            log.debug("Solicitação id={} alterada concorrentemente durante a reconciliação", requestId);
        }
    }

    private Optional<Claim> claim(UUID requestId) {
        try {
            return transactionTemplate.execute(status -> {
                var now = time.now();
                return repository.findById(requestId)
                        .filter(r -> r.isDue(now))
                        .map(r -> {
                            r.startProcessing(now);
                            ActivationRequest saved = repository.saveAndFlush(r);
                            return new Claim(saved.getVersion(), new ProviderActivationCommand(
                                    saved.getId(), saved.getCustomerId(), saved.getProductCode()));
                        });
            });
        } catch (OptimisticLockingFailureException e) {
            log.debug("Solicitação id={} já reivindicada por outro worker", requestId);
            return Optional.empty();
        }
    }

    private ProviderResult callProvider(ProviderActivationCommand command) {
        Timer.Sample sample = Timer.start(meterRegistry);
        ProviderResult result = provider.activate(command);
        sample.stop(Timer.builder("activation.provider.calls")
                .description("Chamadas ao provedor de ativação por resultado")
                .tag("outcome", outcomeTag(result))
                .publishPercentileHistogram()
                .register(meterRegistry));
        return result;
    }

    private void record(UUID requestId, long claimedVersion, ProviderResult result) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                var request = repository.findById(requestId).orElseThrow();
                if (request.getVersion() != claimedVersion) {
                    // Outro processo (reconciliador) já mexeu nela; nosso resultado ficou obsoleto.
                    // Seguro descartar: o reenvio usa a mesma chave de idempotência.
                    log.warn("Resultado obsoleto descartado id={} result={}", requestId, result);
                    return;
                }
                apply(request, result);
                repository.saveAndFlush(request);
                log.info("Solicitação id={} status={} attempts={} result={}",
                        request.getId(), request.getStatus(), request.getAttempts(), result);
            });
        } catch (OptimisticLockingFailureException e) {
            log.warn("Conflito ao registrar resultado id={}; será reconciliado", requestId);
        }
    }

    private void apply(ActivationRequest request, ProviderResult result) {
        var now = time.now();
        switch (result) {
            case ProviderResult.Activated activated -> request.complete(now);
            case ProviderResult.Rejected rejected -> request.fail(
                    "PROVIDER_REJECTED: HTTP " + rejected.httpStatus() + detail(rejected.detail()), now);
            case ProviderResult.TemporaryFailure failure -> {
                if (retryPolicy.canRetry(request.getAttempts())) {
                    request.retryLater(failure.reason(),
                            retryPolicy.nextAttemptAt(now, request.getAttempts(), failure.retryAfter()), now);
                } else {
                    request.fail("MAX_ATTEMPTS_EXCEEDED: " + failure.reason(), now);
                }
            }
        }
    }

    private static String detail(String detail) {
        return detail == null || detail.isBlank() ? "" : " - " + detail;
    }

    private static String outcomeTag(ProviderResult result) {
        return switch (result) {
            case ProviderResult.Activated a -> "activated";
            case ProviderResult.Rejected r -> "rejected";
            case ProviderResult.TemporaryFailure t -> "temporary_failure";
        };
    }

    private record Claim(long version, ProviderActivationCommand command) {
    }
}
