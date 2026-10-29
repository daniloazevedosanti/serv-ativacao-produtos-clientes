package com.desafio.activation.domain;

public class InvalidStatusTransitionException extends RuntimeException {

    private final ActivationStatus from;
    private final ActivationStatus to;

    public InvalidStatusTransitionException(ActivationStatus from, ActivationStatus to) {
        super("Transição de status inválida: " + from + " -> " + to);
        this.from = from;
        this.to = to;
    }

    public ActivationStatus from() {
        return from;
    }

    public ActivationStatus to() {
        return to;
    }
}
