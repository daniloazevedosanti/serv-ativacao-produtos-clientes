package com.desafio.activation.application;

import java.time.Duration;
import java.time.Instant;
import java.util.function.DoubleSupplier;

/**
 * Backoff exponencial com "equal jitter" (metade fixa, metade aleatória): solicitações que falharam
 * juntas durante uma queda do provedor não voltam todas no mesmo segundo.
 */
public class RetryPolicy {

    private final int maxAttempts;
    private final Duration initialBackoff;
    private final double multiplier;
    private final Duration maxBackoff;
    private final Duration maxRetryAfter;
    private final DoubleSupplier random;

    public RetryPolicy(int maxAttempts, Duration initialBackoff, double multiplier,
                       Duration maxBackoff, Duration maxRetryAfter, DoubleSupplier random) {
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.multiplier = multiplier;
        this.maxBackoff = maxBackoff;
        this.maxRetryAfter = maxRetryAfter;
        this.random = random;
    }

    public boolean canRetry(int attemptsSoFar) {
        return attemptsSoFar < maxAttempts;
    }

    public Instant nextAttemptAt(Instant now, int attemptsSoFar, Duration retryAfter) {
        Duration delay = backoff(attemptsSoFar);
        if (retryAfter != null && !retryAfter.isNegative()) {
            Duration requested = retryAfter.compareTo(maxRetryAfter) > 0 ? maxRetryAfter : retryAfter;
            if (requested.compareTo(delay) > 0) {
                delay = requested;
            }
        }
        return now.plus(delay);
    }

    Duration backoff(int attemptsSoFar) {
        int exponent = Math.max(0, attemptsSoFar - 1);
        double baseMillis = initialBackoff.toMillis() * Math.pow(multiplier, exponent);
        double capped = Math.min(baseMillis, maxBackoff.toMillis());
        double half = capped / 2;
        return Duration.ofMillis((long) (half + random.getAsDouble() * half));
    }

    public int maxAttempts() {
        return maxAttempts;
    }
}
