package com.desafio.activation.web.filter;

import com.desafio.activation.web.TraceIdProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Com o X-Trace-Id em mãos, o suporte acha todos os logs da requisição, inclusive os da chamada ao provedor.
 */
public class TraceIdResponseFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";

    private final TraceIdProvider traceIdProvider;

    public TraceIdResponseFilter(TraceIdProvider traceIdProvider) {
        this.traceIdProvider = traceIdProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = traceIdProvider.currentTraceId();
        if (traceId != null) {
            response.setHeader(HEADER, traceId);
        }
        chain.doFilter(request, response);
    }
}
