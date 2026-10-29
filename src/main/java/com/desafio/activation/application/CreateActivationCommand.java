package com.desafio.activation.application;

public record CreateActivationCommand(String externalId, String customerId, String productCode, String clientId) {
}
