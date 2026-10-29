package com.desafio.activation.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Validação dos access tokens.
 *
 * @param issuer     emissor esperado (claim {@code iss})
 * @param audience   audiência esperada (claim {@code aud}): impede reutilizar aqui um token emitido para outra API
 * @param jwkSetUri  endpoint JWKS do servidor de autorização (produção: chaves assimétricas, com rotação)
 * @param hmacSecret segredo HS256 — <b>somente</b> para desenvolvimento local e testes
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
