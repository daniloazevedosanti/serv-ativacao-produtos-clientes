package com.desafio.activation.web;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * client_id é o claim da RFC 9068; azp cobre Keycloak/OIDC; sub é o último recurso.
 */
public final class AuthenticatedClient {

    private AuthenticatedClient() {
    }

    public static Optional<String> find(Jwt jwt) {
        return Stream.of("client_id", "azp", "sub")
                .map(jwt::getClaimAsString)
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }

    /** O decoder já recusa tokens sem identificação, então aqui ela sempre existe. */
    public static String idOf(Jwt jwt) {
        return find(jwt).orElseThrow(() -> new IllegalStateException("Token sem identificação do cliente"));
    }
}
