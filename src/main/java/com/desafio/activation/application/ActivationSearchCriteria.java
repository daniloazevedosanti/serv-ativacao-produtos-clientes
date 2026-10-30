package com.desafio.activation.application;

import com.desafio.activation.domain.ActivationStatus;

/** Campos nulos não filtram. */
public record ActivationSearchCriteria(ActivationStatus status, String customerId) {
}
