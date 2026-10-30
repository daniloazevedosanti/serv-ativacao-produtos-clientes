package com.desafio.activation.infrastructure.provider;

import java.time.Duration;

/**
 * Selada de propósito: um resultado novo quebra a compilação do switch no dispatcher até ser tratado.
 */
public sealed interface ProviderResult {

    record Activated() implements ProviderResult {
    }

    record Rejected(int httpStatus, String detail) implements ProviderResult {
    }

    /** {@code retryAfter} é nulo quando o provedor não mandou o header. */
    record TemporaryFailure(String reason, Duration retryAfter) implements ProviderResult {
    }
}
