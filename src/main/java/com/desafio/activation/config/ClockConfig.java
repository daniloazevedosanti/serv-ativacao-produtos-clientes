package com.desafio.activation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    /** Relógio injetável: facilita testes de tempo (backoff, lease) e garante UTC. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
