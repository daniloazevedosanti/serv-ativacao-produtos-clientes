package com.desafio.activation.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code hmacSecret} existe só para desenvolvimento e testes; o perfil prod recusa subir com ele.
 */
@Validated
@ConfigurationProperties("activation.security.jwt")
public record JwtProperties(
        @NotBlank String issuer,
        @NotBlank String audience,
        String jwkSetUri,
        String hmacSecret) {

    @Override
    public String toString() {
        return "JwtProperties[issuer=" + issuer + ", audience=" + audience + ", jwkSetUri=" + jwkSetUri
                + ", hmacSecret=" + (hmacSecret == null ? "null" : "****") + "]";
    }
}
