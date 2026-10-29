package com.desafio.activation.application;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-26T14:00:00Z");

    private RetryPolicy policy(double random) {
        return new RetryPolicy(5, Duration.ofSeconds(2), 2.0, Duration.ofSeconds(60), Duration.ofMinutes(5),
                () -> random);
    }

    @Test
    void backoff_exponencial_com_jitter_entre_metade_e_o_total() {
        assertThat(policy(0.0).backoff(1)).isEqualTo(Duration.ofSeconds(1));   // 2s * [0.5..1]
        assertThat(policy(1.0).backoff(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy(1.0).backoff(2)).isEqualTo(Duration.ofSeconds(4));
        assertThat(policy(1.0).backoff(3)).isEqualTo(Duration.ofSeconds(8));
    }

    @Test
    void backoff_respeita_o_teto() {
        assertThat(policy(1.0).backoff(20)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void retry_after_do_provedor_e_respeitado_quando_maior() {
        assertThat(policy(1.0).nextAttemptAt(NOW, 1, Duration.ofSeconds(30))).isEqualTo(NOW.plusSeconds(30));
        assertThat(policy(1.0).nextAttemptAt(NOW, 3, Duration.ofSeconds(1))).isEqualTo(NOW.plusSeconds(8));
    }

    @Test
    void retry_after_absurdo_e_limitado() {
        assertThat(policy(1.0).nextAttemptAt(NOW, 1, Duration.ofDays(1))).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
    }

    @Test
    void limite_de_tentativas() {
        var policy = policy(0.5);
        assertThat(policy.canRetry(4)).isTrue();
        assertThat(policy.canRetry(5)).isFalse();
    }
}
