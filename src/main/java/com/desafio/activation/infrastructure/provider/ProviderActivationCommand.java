package com.desafio.activation.infrastructure.provider;

import java.util.UUID;

/**
 * O id da solicitação vira a Idempotency-Key: é o mesmo em todas as tentativas, inclusive após timeout.
 */
public record ProviderActivationCommand(UUID requestId, String customerId, String productCode) {

    public String idempotencyKey() {
        return requestId.toString();
    }
}
