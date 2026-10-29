package com.desafio.activation.application;

import java.util.UUID;

/** Evento interno publicado dentro da transação de criação; consumido somente após o commit. */
public record ActivationRequestCreated(UUID requestId) {
}
