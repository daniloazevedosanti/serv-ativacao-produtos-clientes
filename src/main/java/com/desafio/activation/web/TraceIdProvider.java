package com.desafio.activation.web;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** traceId da requisição atual (o mesmo que aparece nos logs), para devolver ao consumidor. */
@Component
public class TraceIdProvider {

    private final ObjectProvider<Tracer> tracer;

    public TraceIdProvider(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    public String currentTraceId() {
        Tracer t = tracer.getIfAvailable();
        if (t != null) {
            Span span = t.currentSpan();
            if (span != null && hasText(span.context().traceId())) {
                return span.context().traceId();
            }
        }
        String fromMdc = MDC.get("traceId");
        return hasText(fromMdc) ? fromMdc : null; // tracer no-op devolve traceId vazio
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
