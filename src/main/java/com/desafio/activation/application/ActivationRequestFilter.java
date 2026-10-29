package com.desafio.activation.application;

import com.desafio.activation.domain.ActivationStatus;

/** Filtros opcionais da listagem; {@code null} significa "sem filtro". */
public record ActivationRequestFilter(ActivationStatus status, String customerId) {
}
