package com.desafio.activation.application;

import java.util.UUID;

/**
 * Dados enviados ao provedor. O {@code requestId} também é usado como chave de idempotência:
 * se reenviarmos após um timeout, o provedor reconhece a mesma operação e não ativa duas vezes.
 */
public record ProviderActivationCommand(UUID requestId, String customerId, String productCode) {

    public String idempotencyKey() {
        return requestId.toString();
    }
}
