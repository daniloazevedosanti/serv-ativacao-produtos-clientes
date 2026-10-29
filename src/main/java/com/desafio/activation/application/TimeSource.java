package com.desafio.activation.application;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Instante atual truncado em milissegundos. O PostgreSQL guarda microssegundos e a JVM gera
 * nanossegundos: sem truncar, o {@code createdAt} devolvido no POST seria diferente do lido no GET.
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
