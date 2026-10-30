package com.desafio.activation.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Emite tokens HS256 assinados de verdade para os testes — exercita a validação real
 * (assinatura, emissor, audiência, expiração) sem precisar de servidor de autenticação.
 */
public final class TestJwt {

    public static final String SECRET = "test-secret-with-at-least-32-bytes-0123456789";
    public static final String ISSUER = "https://issuer.test";
    public static final String AUDIENCE = "activation-api";
    public static final String READ_WRITE = "activation:read activation:write";

    private String clientId = "crm-system";
    private boolean anonymous;
    private String scope = READ_WRITE;
    private String issuer = ISSUER;
    private String audience = AUDIENCE;
    private String secret = SECRET;
    private Instant expiresAt = Instant.now().plus(Duration.ofMinutes(5));

    private TestJwt() {
    }

    public static TestJwt token() {
        return new TestJwt();
    }

    public TestJwt clientId(String clientId) {
        this.clientId = clientId;
        return this;
    }

    /** Token assinado e válido, mas sem client_id, azp nem sub. */
    public TestJwt withoutClientIdentity() {
        this.anonymous = true;
        return this;
    }

    public TestJwt scope(String scope) {
        this.scope = scope;
        return this;
    }

    public TestJwt issuer(String issuer) {
        this.issuer = issuer;
        return this;
    }

    public TestJwt audience(String audience) {
        this.audience = audience;
        return this;
    }

    public TestJwt secret(String secret) {
        this.secret = secret;
        return this;
    }

    public TestJwt expiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
        return this;
    }

    public String encode() {
        var claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(anonymous ? null : clientId)
                .audience(audience)
                .claim("client_id", anonymous ? null : clientId)
                .claim("scope", scope)
                .issueTime(Date.from(Instant.now().minusSeconds(5)))
                .expirationTime(Date.from(expiresAt))
                .build();
        try {
            var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public String bearer() {
        return "Bearer " + encode();
    }
}
