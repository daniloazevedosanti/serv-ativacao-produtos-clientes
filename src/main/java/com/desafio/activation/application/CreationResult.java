package com.desafio.activation.application;

import com.desafio.activation.domain.ActivationRequest;

/** Resultado da criação: nova solicitação ou repetição idempotente de uma já existente. */
public sealed interface CreationResult {

    ActivationRequest request();

    record Created(ActivationRequest request) implements CreationResult {
    }

    record Replayed(ActivationRequest request) implements CreationResult {
    }
}
