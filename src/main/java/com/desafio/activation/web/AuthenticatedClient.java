package com.desafio.activation.web;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Identifica o sistema consumidor a partir do access token (fluxo client credentials).
 * Ordem: {@code client_id} (RFC 9068), {@code azp} (OIDC/Keycloak) e, por último, {@code sub}.
 */
public final class AuthenticatedClient {

    private AuthenticatedClient() {
    }

    public static String idOf(Jwt jwt) {
        for (String claim : new String[]{"client_id", "azp", "sub"}) {
            String value = jwt.getClaimAsString(claim);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        throw new IllegalStateException("Token sem identificação do cliente");
    }
}
