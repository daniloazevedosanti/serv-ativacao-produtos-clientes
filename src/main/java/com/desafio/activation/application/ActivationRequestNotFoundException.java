package com.desafio.activation.application;

import java.util.UUID;

public class ActivationRequestNotFoundException extends RuntimeException {

    public ActivationRequestNotFoundException(UUID id) {
        super("Solicitação " + id + " não encontrada");
    }
}
