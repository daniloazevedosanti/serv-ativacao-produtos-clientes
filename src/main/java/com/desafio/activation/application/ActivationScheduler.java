package com.desafio.activation.application;

import com.desafio.activation.infrastructure.persistence.ActivationRequestRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * A tabela é a outbox: o que estiver PENDING e vencido sai daqui, mesmo que o disparo pós-commit
 * tenha se perdido (instância caiu, pool cheio). Várias instâncias podem rodar isto ao mesmo tempo;
 * o claim com lock otimista decide quem envia.
 */
@Component
@ConditionalOnProperty(name = "activation.dispatch.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class ActivationScheduler {

    private final ActivationRequestRepository repository;
    private final ActivationDispatcher dispatcher;
    private final DispatchProperties properties;
    private final TimeSource time;

    public ActivationScheduler(ActivationRequestRepository repository, ActivationDispatcher dispatcher,
                               DispatchProperties properties, TimeSource time) {
        this.repository = repository;
        this.dispatcher = dispatcher;
        this.properties = properties;
        this.time = time;
    }

    @Scheduled(fixedDelayString = "${activation.dispatch.poll-interval-ms:1000}")
    public void dispatchDue() {
        repository.findDueIds(time.now(), PageRequest.ofSize(properties.batchSize()))
                .forEach(dispatcher::dispatch);
    }

    @Scheduled(fixedDelayString = "${activation.dispatch.reconcile-interval-ms:30000}")
    public void reconcileStuck() {
        var threshold = time.now().minus(properties.processingLease());
        repository.findStuckProcessingIds(threshold, PageRequest.ofSize(properties.batchSize()))
                .forEach(id -> dispatcher.releaseIfStuck(id, threshold));
    }
}
