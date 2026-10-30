package com.desafio.activation.application;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Trunca em milissegundos: o PostgreSQL guarda micro e a JVM gera nano, e sem isso o createdAt
 * devolvido no POST não bate com o lido no GET.
 */
@Component
public class TimeSource {

    private final Clock clock;

    public TimeSource(Clock clock) {
        this.clock = clock;
    }

    public Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MILLIS);
    }
}
