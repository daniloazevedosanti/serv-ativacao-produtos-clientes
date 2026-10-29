package com.desafio.activation.application;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Política de envio ao provedor.
 *
 * @param maxAttempts      número máximo de tentativas antes de marcar FAILED
 * @param initialBackoff   espera antes da 2ª tentativa
 * @param backoffMultiplier crescimento exponencial da espera
 * @param maxBackoff       teto da espera calculada
 * @param maxRetryAfter    teto para o {@code Retry-After} informado pelo provedor
 * @param processingLease  tempo após o qual uma solicitação em PROCESSING é considerada "presa"
 * @param batchSize        quantas solicitações o worker busca por ciclo
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
