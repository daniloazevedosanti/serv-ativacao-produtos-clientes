package com.desafio.activation.application;

import com.desafio.activation.domain.ActivationRequest;
import com.desafio.activation.domain.ActivationStatus;
import com.desafio.activation.infrastructure.persistence.ActivationRequestRepository;
import com.desafio.activation.infrastructure.provider.ActivationProvider;
import com.desafio.activation.infrastructure.provider.ProviderActivationCommand;
import com.desafio.activation.infrastructure.provider.ProviderResult;
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
 * Envia uma solicitação ao provedor em três passos: claim (PENDING → PROCESSING), chamada HTTP e
 * registro do resultado. Cada passo de banco é uma transação curta e a chamada HTTP fica fora
 * delas, para que um provedor lento não segure conexões do pool.
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

    public void dispatch(UUID requestId) {
        try {
            claim(requestId).ifPresent(claim -> {
                ProviderResult outcome = callProvider(claim.command());
                recordOutcome(requestId, claim.version(), outcome);
            });
        } catch (RuntimeException e) {
            // A solicitação fica em PROCESSING e o reconciliador devolve para a fila depois do lease.
            log.error("Falha inesperada ao enviar solicitação id={}", requestId, e);
        }
    }

    public void releaseIfStuck(UUID requestId, Instant leaseExpiredBefore) {
        try {
            transactionTemplate.executeWithoutResult(tx -> repository.findById(requestId)
                    .filter(stuck -> stuck.getStatus() == ActivationStatus.PROCESSING)
                    .filter(stuck -> stuck.getUpdatedAt().isBefore(leaseExpiredBefore))
                    .ifPresent(this::requeueOrGiveUp));
        } catch (OptimisticLockingFailureException e) {
            log.debug("Solicitação id={} mudou durante a reconciliação; nada a fazer", requestId);
        }
    }

    private void requeueOrGiveUp(ActivationRequest stuck) {
        var now = time.now();
        if (retryPolicy.canRetry(stuck.getAttempts())) {
            stuck.retryLater("LEASE_EXPIRED", now, now);
        } else {
            stuck.fail("MAX_ATTEMPTS_EXCEEDED: LEASE_EXPIRED", now);
        }
        repository.saveAndFlush(stuck);
        log.warn("Solicitação presa em PROCESSING liberada id={} attempts={} status={}",
                stuck.getId(), stuck.getAttempts(), stuck.getStatus());
    }

    private Optional<Claim> claim(UUID requestId) {
        try {
            return transactionTemplate.execute(tx -> {
                var now = time.now();
                return repository.findById(requestId)
                        .filter(request -> request.isDue(now))
                        .map(request -> {
                            request.startProcessing(now);
                            ActivationRequest claimed = repository.saveAndFlush(request);
                            return new Claim(claimed.getVersion(), new ProviderActivationCommand(
                                    claimed.getId(), claimed.getCustomerId(), claimed.getProductCode()));
                        });
            });
        } catch (OptimisticLockingFailureException e) {
            log.debug("Solicitação id={} já foi reivindicada por outro worker", requestId);
            return Optional.empty();
        }
    }

    private ProviderResult callProvider(ProviderActivationCommand command) {
        Timer.Sample sample = Timer.start(meterRegistry);
        ProviderResult outcome = provider.activate(command);
        sample.stop(Timer.builder("activation.provider.calls")
                .description("Chamadas ao provedor de ativação por resultado")
                .tag("outcome", metricTag(outcome))
                .publishPercentileHistogram()
                .register(meterRegistry));
        return outcome;
    }

    private void recordOutcome(UUID requestId, long claimedVersion, ProviderResult outcome) {
        try {
            transactionTemplate.executeWithoutResult(tx -> {
                var request = repository.findById(requestId).orElseThrow();
                if (request.getVersion() != claimedVersion) {
                    // O reconciliador já reabriu a solicitação. Descartar é seguro: o reenvio usa a mesma Idempotency-Key.
                    log.warn("Resultado obsoleto descartado id={} outcome={}", requestId, outcome);
                    return;
                }
                applyOutcome(request, outcome);
                repository.saveAndFlush(request);
                log.info("Solicitação id={} status={} attempts={} outcome={}",
                        request.getId(), request.getStatus(), request.getAttempts(), outcome);
            });
        } catch (OptimisticLockingFailureException e) {
            log.warn("Conflito ao gravar resultado id={}; fica para a reconciliação", requestId);
        }
    }

    private void applyOutcome(ActivationRequest request, ProviderResult outcome) {
        var now = time.now();
        switch (outcome) {
            case ProviderResult.Activated activated -> request.complete(now);
            case ProviderResult.Rejected rejected -> request.fail(rejectionReason(rejected), now);
            case ProviderResult.TemporaryFailure failure -> {
                if (retryPolicy.canRetry(request.getAttempts())) {
                    var nextAttempt = retryPolicy.nextAttemptAt(now, request.getAttempts(), failure.retryAfter());
                    request.retryLater(failure.reason(), nextAttempt, now);
                } else {
                    request.fail("MAX_ATTEMPTS_EXCEEDED: " + failure.reason(), now);
                }
            }
        }
    }

    private static String rejectionReason(ProviderResult.Rejected rejected) {
        String reason = "PROVIDER_REJECTED: HTTP " + rejected.httpStatus();
        return rejected.detail() == null || rejected.detail().isBlank() ? reason : reason + " - " + rejected.detail();
    }

    private static String metricTag(ProviderResult outcome) {
        return switch (outcome) {
            case ProviderResult.Activated activated -> "activated";
            case ProviderResult.Rejected rejected -> "rejected";
            case ProviderResult.TemporaryFailure failure -> "temporary_failure";
        };
    }

    private record Claim(long version, ProviderActivationCommand command) {
    }
}
