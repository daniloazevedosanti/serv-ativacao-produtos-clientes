package com.desafio.activation.infrastructure.provider;

import java.time.Duration;

/**
 * Resultado de uma chamada ao provedor de ativação, já classificado pela camada de integração.
 * Hierarquia selada: o {@code switch} que consome este tipo é verificado pelo compilador
 * (adicionar um novo caso quebra a compilação até ele ser tratado).
 */
public sealed interface ProviderResult {

    /** 2xx: produto ativado. */
    record Activated() implements ProviderResult {
    }

    /** Erro definitivo (ex.: HTTP 400): repetir a mesma requisição não vai mudar o resultado. */
    record Rejected(int httpStatus, String detail) implements ProviderResult {
    }

    /**
     * Erro temporário (5xx, 429, timeout, falha de conexão): pode ser tentado novamente.
     *
     * @param retryAfter prazo sugerido pelo provedor (header {@code Retry-After}); pode ser {@code null}
     */
    record TemporaryFailure(String reason, Duration retryAfter) implements ProviderResult {
    }
}
