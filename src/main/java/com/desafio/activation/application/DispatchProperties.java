package com.desafio.activation.application;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * {@code maxRetryAfter} evita que um Retry-After absurdo do provedor congele a fila;
 * {@code processingLease} precisa ser bem maior que connect + read timeout.
 */
@Validated
@ConfigurationProperties("activation.dispatch")
public record DispatchProperties(
        @Min(1) int maxAttempts,
        @NotNull Duration initialBackoff,
        @DecimalMin("1.0") double backoffMultiplier,
        @NotNull Duration maxBackoff,
        @NotNull Duration maxRetryAfter,
        @NotNull Duration processingLease,
        @Min(1) int batchSize) {
}
