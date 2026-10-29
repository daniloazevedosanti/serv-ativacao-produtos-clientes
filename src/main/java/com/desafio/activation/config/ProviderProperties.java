package com.desafio.activation.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

/**
 * @param baseUrl        URL base do provedor
 * @param connectTimeout tempo máximo para abrir a conexão
 * @param readTimeout    tempo máximo esperando a resposta
 * @param apiKey         credencial do provedor (vem de variável de ambiente/cofre de segredos; nunca logada)
 */
@Validated
@ConfigurationProperties("activation.provider")
public record ProviderProperties(
        @NotNull URI baseUrl,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        String apiKey) {

    @Override
    public String toString() {
        // evita vazar a credencial em logs/actuator
        return "ProviderProperties[baseUrl=" + baseUrl + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + ", apiKey=****]";
    }
}
