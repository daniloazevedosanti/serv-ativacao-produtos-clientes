package com.desafio.activation.application;

import com.desafio.activation.domain.ActivationRequest;
import com.desafio.activation.infrastructure.persistence.ActivationRequestRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static com.desafio.activation.infrastructure.persistence.ActivationRequestSpecifications.hasCustomerId;
import static com.desafio.activation.infrastructure.persistence.ActivationRequestSpecifications.hasStatus;
import static com.desafio.activation.infrastructure.persistence.ActivationRequestSpecifications.ownedBy;

@Service
public class ActivationRequestService {

    private static final Logger log = LoggerFactory.getLogger(ActivationRequestService.class);

    private final ActivationRequestRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher events;
    private final TimeSource time;
    private final Counter createdCounter;
    private final Counter replayedCounter;
    private final Counter conflictCounter;

    public ActivationRequestService(ActivationRequestRepository repository,
                                    TransactionTemplate transactionTemplate,
                                    ApplicationEventPublisher events,
                                    TimeSource time,
                                    MeterRegistry meterRegistry) {
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.events = events;
        this.time = time;
        this.createdCounter = creationCounter(meterRegistry, "created");
        this.replayedCounter = creationCounter(meterRegistry, "replayed");
        this.conflictCounter = creationCounter(meterRegistry, "conflict");
    }

    /**
     * Cria a solicitação de forma idempotente por {@code externalId}.
     *
     * <p>A consulta prévia é só um atalho para o caso comum (retry do sistema de origem).
     * Quem garante a unicidade é a constraint UNIQUE do banco: se duas requisições passarem
     * juntas pela consulta, a segunda falha no INSERT e é tratada como repetição/conflito.
     */
    public CreationResult create(CreateActivationCommand command) {
        var existing = repository.findByExternalId(command.externalId());
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), command);
        }

        var request = ActivationRequest.create(command.externalId(), command.customerId(),
                command.productCode(), command.clientId(), time.now());
        try {
            transactionTemplate.executeWithoutResult(status -> {
                repository.saveAndFlush(request);
                // entregue ao listener somente após o COMMIT (@TransactionalEventListener)
                events.publishEvent(new ActivationRequestCreated(request.getId()));
            });
        } catch (DataIntegrityViolationException e) {
            // Corrida: outra requisição inseriu o mesmo externalId entre o SELECT e o INSERT.
            // A releitura acontece em nova transação (a anterior foi abortada pelo banco).
            var winner = repository.findByExternalId(command.externalId()).orElseThrow(() -> e);
            log.info("Inserção concorrente detectada para externalId={}", command.externalId());
            return replayOrConflict(winner, command);
        }
        createdCounter.increment();
        log.info("Solicitação criada id={} externalId={} clientId={}",
                request.getId(), request.getExternalId(), request.getClientId());
        return new CreationResult.Created(request);
    }

    public ActivationRequest get(UUID id, String clientId) {
        // Filtrar pelo dono na própria query: solicitação de outro consumidor = "não existe" (404).
        return repository.findByIdAndClientId(id, clientId)
                .orElseThrow(() -> new ActivationRequestNotFoundException(id));
    }

    public Page<ActivationRequest> list(ActivationRequestFilter filter, String clientId, Pageable pageable) {
        Specification<ActivationRequest> spec = ownedBy(clientId)
                .and(hasStatus(filter.status()))
                .and(hasCustomerId(filter.customerId()));
        return repository.findAll(spec, pageable);
    }

    private CreationResult replayOrConflict(ActivationRequest existing, CreateActivationCommand command) {
        if (existing.hasSameContent(command.customerId(), command.productCode(), command.clientId())) {
            replayedCounter.increment();
            return new CreationResult.Replayed(existing);
        }
        conflictCounter.increment();
        throw new DuplicateExternalIdException(command.externalId());
    }

    private static Counter creationCounter(MeterRegistry registry, String outcome) {
        return Counter.builder("activation.requests.creation")
                .description("Requisições de criação por resultado")
                .tag("outcome", outcome)
                .register(registry);
    }
}
