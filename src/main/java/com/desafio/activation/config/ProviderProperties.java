package com.desafio.activation.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

@Validated
@ConfigurationProperties("activation.provider")
public record ProviderProperties(
        @NotNull URI baseUrl,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        String apiKey) {

    @Override
    public String toString() {
        // records geram toString com todos os campos; a chave não pode ir parar em log
        return "ProviderProperties[baseUrl=" + baseUrl + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + ", apiKey=****]";
    }
}
