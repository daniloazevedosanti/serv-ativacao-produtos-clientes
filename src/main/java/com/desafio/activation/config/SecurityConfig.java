package com.desafio.activation.config;

import com.desafio.activation.web.AuthenticatedClient;
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
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    static final String SCOPE_READ = "SCOPE_activation:read";
    static final String SCOPE_WRITE = "SCOPE_activation:write";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemResponseWriter writer) throws Exception {
        var problemHandler = new ProblemDetailsSecurityHandler(writer);
        http
                // Só bearer token, sem cookie de sessão: CSRF não se aplica.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // em prod o actuator fica na porta 8081, que não é publicada
                        .requestMatchers(EndpointRequest.toAnyEndpoint()).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/requests").hasAuthority(SCOPE_WRITE)
                        .requestMatchers(HttpMethod.GET, "/api/v1/requests", "/api/v1/requests/**")
                        .hasAuthority(SCOPE_READ)
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
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

    @Bean
    JwtDecoder jwtDecoder(JwtProperties properties, Environment environment) {
        NimbusJwtDecoder decoder;
        if (hasText(properties.jwkSetUri())) {
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
                new AudienceValidator(properties.audience()),
                SecurityConfig::requireClientIdentity));
        return decoder;
    }

    // Sem isso, um token sem client_id/azp/sub só falharia dentro do controller, como 500.
    private static OAuth2TokenValidatorResult requireClientIdentity(Jwt jwt) {
        return AuthenticatedClient.find(jwt).isPresent()
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN,
                "O token não identifica o cliente (client_id, azp ou sub)", null));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
