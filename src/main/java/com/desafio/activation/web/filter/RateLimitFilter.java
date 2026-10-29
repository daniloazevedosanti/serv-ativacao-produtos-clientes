package com.desafio.activation.web.filter;

import com.desafio.activation.web.AuthenticatedClient;
import com.desafio.activation.web.ProblemTypes;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Limite de taxa por consumidor (token bucket). Roda depois do Spring Security para usar o
 * {@code client_id} do token como chave.
 *
 * <p>Limitação consciente: o estado é por instância (com N instâncias o limite efetivo é N×).
 * Em produção o lugar natural é o API Gateway ou um contador compartilhado (ex.: Redis);
 * aqui ele protege a instância contra um consumidor descontrolado.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final double capacity;
    private final double refillPerNano;
    private final ProblemResponseWriter problemWriter;
    private final LongSupplier nanoClock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(int requestsPerSecond, int burst, ProblemResponseWriter problemWriter) {
        this(requestsPerSecond, burst, problemWriter, System::nanoTime);
    }

    RateLimitFilter(int requestsPerSecond, int burst, ProblemResponseWriter problemWriter, LongSupplier nanoClock) {
        this.capacity = Math.max(burst, 1);
        this.refillPerNano = requestsPerSecond / 1_000_000_000d;
        this.problemWriter = problemWriter;
        this.nanoClock = nanoClock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token) {
            String clientId = AuthenticatedClient.idOf(token.getToken());
            Bucket bucket = buckets.computeIfAbsent(clientId, k -> new Bucket(capacity, nanoClock.getAsLong()));
            if (!bucket.tryConsume(nanoClock.getAsLong())) {
                response.setHeader(HttpHeaders.RETRY_AFTER, "1");
                problemWriter.write(request, response, HttpStatus.TOO_MANY_REQUESTS, ProblemTypes.RATE_LIMITED,
                        "Muitas requisições", "Limite de requisições excedido. Tente novamente em instantes.");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private final class Bucket {

        private double tokens;
        private long lastRefill;

        Bucket(double tokens, long now) {
            this.tokens = tokens;
            this.lastRefill = now;
        }

        synchronized boolean tryConsume(long now) {
            tokens = Math.min(capacity, tokens + (now - lastRefill) * refillPerNano);
            lastRefill = now;
            if (tokens >= 1) {
                tokens -= 1;
                return true;
            }
            return false;
        }
    }
}
