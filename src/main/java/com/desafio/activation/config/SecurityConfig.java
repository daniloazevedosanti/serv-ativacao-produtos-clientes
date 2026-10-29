package com.desafio.activation.config;

import com.desafio.activation.web.filter.ProblemResponseWriter;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * API protegida como OAuth 2.0 Resource Server (tokens JWT emitidos por um servidor de
 * autorização no fluxo client credentials — comunicação sistema-a-sistema).
 *
 * <ul>
 *   <li>{@code POST /api/v1/requests} exige escopo {@code activation:write}</li>
 *   <li>{@code GET  /api/v1/requests/**} exige escopo {@code activation:read}</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    static final String SCOPE_READ = "SCOPE_activation:read";
    static final String SCOPE_WRITE = "SCOPE_activation:write";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemResponseWriter writer) throws Exception {
        var problemHandler = new ProblemDetailsSecurityHandler(writer);
        http
                // API stateless com bearer token: não há cookie de sessão a ser explorado via CSRF
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // health/métricas: em produção ficam em porta de gestão separada, não exposta publicamente
                        .requestMatchers(EndpointRequest.toAnyEndpoint()).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/requests").hasAuthority(SCOPE_WRITE)
                        .requestMatchers(HttpMethod.GET, "/api/v1/requests", "/api/v1/requests/**")
                        .hasAuthority(SCOPE_READ)
                        .requestMatchers("/error").permitAll()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> {
                        })
                        .authenticationEntryPoint(problemHandler)
                        .accessDeniedHandler(problemHandler))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(problemHandler)
                        .accessDeniedHandler(problemHandler));
        return http.build();
    }

    /**
     * Valida assinatura, expiração ({@code exp}/{@code nbf}, com tolerância de relógio),
     * emissor ({@code iss}) e audiência ({@code aud}).
     */
    @Bean
    JwtDecoder jwtDecoder(JwtProperties properties, Environment environment) {
        NimbusJwtDecoder decoder;
        if (hasText(properties.jwkSetUri())) {
            // Produção: chave pública obtida (e cacheada) do JWKS; suporta rotação de chaves.
            decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
        } else if (hasText(properties.hmacSecret())) {
            if (environment.acceptsProfiles(Profiles.of("prod"))) {
                throw new IllegalStateException("Segredo HMAC não é permitido no perfil prod; configure jwk-set-uri");
            }
            byte[] secret = properties.hmacSecret().getBytes(StandardCharsets.UTF_8);
            if (secret.length < 32) {
                throw new IllegalStateException("activation.security.jwt.hmac-secret precisa de ao menos 32 bytes");
            }
            decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(secret, "HmacSHA256"))
                    .macAlgorithm(MacAlgorithm.HS256)
                    .build();
        } else {
            throw new IllegalStateException(
                    "Configure activation.security.jwt.jwk-set-uri (ou hmac-secret em desenvolvimento)");
        }
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer()),
                new AudienceValidator(properties.audience())));
        return decoder;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
