package com.desafio.activation.domain;

/**
 * Estados de uma solicitação de ativação.
 *
 * <pre>
 *   PENDING ──► PROCESSING ──► COMPLETED   (terminal)
 *      ▲            │
 *      └────────────┤ falha temporária: volta para PENDING com nova data de tentativa
 *                   └──► FAILED            (terminal)
 * </pre>
 */
public enum ActivationStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED;

    public boolean canTransitionTo(ActivationStatus target) {
        return switch (this) {
            case PENDING -> target == PROCESSING;
            case PROCESSING -> target == COMPLETED || target == FAILED || target == PENDING;
            case COMPLETED, FAILED -> false;
        };
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
