package com.desafio.activation.web.filter;

import com.desafio.activation.web.TraceIdProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    private final AtomicLong nanos = new AtomicLong();
    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        var writer = new ProblemResponseWriter(new ObjectMapper(),
                new TraceIdProvider(new StaticListableBeanFactory().getBeanProvider(io.micrometer.tracing.Tracer.class)));
        filter = new RateLimitFilter(2, 2, writer, nanos::get); // 2 req/s, rajada de 2
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private int call(String clientId) throws Exception {
        var jwt = Jwt.withTokenValue("t").header("alg", "HS256").claim("client_id", clientId)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/requests"), response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    void bloqueia_acima_da_rajada_e_libera_apos_reabastecer() throws Exception {
        assertThat(call("crm")).isEqualTo(200);
        assertThat(call("crm")).isEqualTo(200);
        assertThat(call("crm")).isEqualTo(429);

        nanos.addAndGet(500_000_000L); // +0,5s = 1 token

        assertThat(call("crm")).isEqualTo(200);
        assertThat(call("crm")).isEqualTo(429);
    }

    @Test
    void limite_e_independente_por_consumidor() throws Exception {
        call("crm");
        call("crm");
        assertThat(call("crm")).isEqualTo(429);
        assertThat(call("billing")).isEqualTo(200);
    }

    @Test
    void resposta_429_tem_retry_after_e_problem_details() throws Exception {
        call("crm");
        call("crm");
        var jwt = Jwt.withTokenValue("t").header("alg", "HS256").claim("client_id", "crm").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        var response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/requests"), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString()).contains("rate-limited");
    }
}
