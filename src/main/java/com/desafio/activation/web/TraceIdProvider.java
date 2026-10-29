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
            if (span != null) {
                return span.context().traceId();
            }
        }
        return MDC.get("traceId");
    }
}
