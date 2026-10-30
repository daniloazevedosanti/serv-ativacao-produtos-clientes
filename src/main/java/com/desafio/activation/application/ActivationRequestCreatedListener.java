package com.desafio.activation.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Atalho para o caminho feliz não esperar o polling. Se o pool estiver cheio, descartar é seguro:
 * o {@link ActivationScheduler} pega a solicitação no ciclo seguinte.
 */
@Component
public class ActivationRequestCreatedListener {

    private static final Logger log = LoggerFactory.getLogger(ActivationRequestCreatedListener.class);

    private final ActivationDispatcher dispatcher;
    private final TaskExecutor executor;

    public ActivationRequestCreatedListener(ActivationDispatcher dispatcher,
                                            @Qualifier("providerDispatchExecutor") TaskExecutor executor) {
        this.dispatcher = dispatcher;
        this.executor = executor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCreated(ActivationRequestCreated event) {
        try {
            executor.execute(() -> dispatcher.dispatch(event.requestId()));
        } catch (TaskRejectedException e) {
            log.debug("Executor cheio; solicitação id={} será enviada pelo agendador", event.requestId());
        }
    }
}
