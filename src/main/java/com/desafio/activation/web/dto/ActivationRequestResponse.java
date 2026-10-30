package com.desafio.activation.web.dto;

import com.desafio.activation.domain.ActivationRequest;
import com.desafio.activation.domain.ActivationStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

public record ActivationRequestResponse(
        UUID id,
        String externalId,
        String customerId,
        String productCode,
        ActivationStatus status,
        int attempts,
        @JsonInclude(JsonInclude.Include.NON_NULL) String failureReason,
        Instant createdAt,
        Instant updatedAt) {

    public static ActivationRequestResponse from(ActivationRequest request) {
        return new ActivationRequestResponse(request.getId(), request.getExternalId(), request.getCustomerId(),
                request.getProductCode(), request.getStatus(), request.getAttempts(), request.getFailureReason(),
                request.getCreatedAt(), request.getUpdatedAt());
    }
}
