package com.desafio.activation.web;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

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
        // o tracer no-op (testes sem observabilidade) devolve traceId vazio, não nulo
        return hasText(fromMdc) ? fromMdc : null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
