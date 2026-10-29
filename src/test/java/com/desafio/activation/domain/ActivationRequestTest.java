package com.desafio.activation.domain;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Regras de domínio, sem Spring e sem banco. */
class ActivationRequestTest {

    private static final Instant T0 = Instant.parse("2026-10-26T14:03:11Z");

    private static ActivationRequest newRequest() {
        return ActivationRequest.create("EXT-1", "CUST-1", "INTERNET-500", "crm", T0);
    }

    @Test
    void nasce_pendente_e_pronta_para_envio() {
        var request = newRequest();

        assertThat(request.getId()).isNotNull();
        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(request.getAttempts()).isZero();
        assertThat(request.getCreatedAt()).isEqualTo(T0);
        assertThat(request.getUpdatedAt()).isEqualTo(T0);
        assertThat(request.isDue(T0)).isTrue();
    }

    @Test
    void campos_obrigatorios_sao_validados_no_dominio() {
        assertThatThrownBy(() -> ActivationRequest.create(" ", "CUST-1", "P", "crm", T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("externalId");
    }

    @Test
    void fluxo_feliz_pending_processing_completed() {
        var request = newRequest();
        request.startProcessing(T0.plusSeconds(1));

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PROCESSING);
        assertThat(request.getAttempts()).isEqualTo(1);
        assertThat(request.isDue(T0.plusSeconds(10))).isFalse();

        request.complete(T0.plusSeconds(2));

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.COMPLETED);
        assertThat(request.getUpdatedAt()).isEqualTo(T0.plusSeconds(2));
        assertThat(request.getFailureReason()).isNull();
    }

    @Test
    void falha_temporaria_volta_para_pendente_com_nova_data() {
        var request = newRequest();
        request.startProcessing(T0);

        request.retryLater("PROVIDER_HTTP_500", T0.plusSeconds(30), T0.plusSeconds(1));

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(request.getFailureReason()).isEqualTo("PROVIDER_HTTP_500");
        assertThat(request.isDue(T0.plusSeconds(29))).isFalse();
        assertThat(request.isDue(T0.plusSeconds(30))).isTrue();

        request.startProcessing(T0.plusSeconds(30));
        assertThat(request.getAttempts()).isEqualTo(2);
    }

    @Test
    void falha_definitiva_registra_motivo_truncado() {
        var request = newRequest();
        request.startProcessing(T0);

        request.fail("x".repeat(1000), T0);

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.FAILED);
        assertThat(request.getFailureReason()).hasSize(500);
    }

    @Nested
    class TransicoesInvalidas {

        @Test
        void completed_nao_volta_para_processing() {
            var request = newRequest();
            request.startProcessing(T0);
            request.complete(T0);

            assertThatThrownBy(() -> request.startProcessing(T0))
                    .isInstanceOf(InvalidStatusTransitionException.class)
                    .hasMessageContaining("COMPLETED -> PROCESSING");
        }

        @Test
        void failed_e_terminal() {
            var request = newRequest();
            request.startProcessing(T0);
            request.fail("x", T0);

            assertThatThrownBy(() -> request.retryLater("y", T0, T0))
                    .isInstanceOf(InvalidStatusTransitionException.class);
        }

        @Test
        void nao_conclui_sem_ter_sido_processada() {
            var request = newRequest();

            assertThatThrownBy(() -> request.complete(T0))
                    .isInstanceOf(InvalidStatusTransitionException.class)
                    .hasMessageContaining("PENDING -> COMPLETED");
            assertThat(request.getStatus()).isEqualTo(ActivationStatus.PENDING);
        }
    }

    @ParameterizedTest(name = "{0} -> {1} permitido? {2}")
    @CsvSource({
            "PENDING,PROCESSING,true", "PENDING,COMPLETED,false", "PENDING,FAILED,false",
            "PROCESSING,COMPLETED,true", "PROCESSING,FAILED,true", "PROCESSING,PENDING,true",
            "COMPLETED,PROCESSING,false", "COMPLETED,PENDING,false", "COMPLETED,FAILED,false",
            "FAILED,PENDING,false", "FAILED,PROCESSING,false", "FAILED,COMPLETED,false"
    })
    void matriz_de_transicoes(ActivationStatus from, ActivationStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }

    @Test
    void compara_conteudo_para_idempotencia() {
        var request = newRequest();

        assertThat(request.hasSameContent("CUST-1", "INTERNET-500", "crm")).isTrue();
        assertThat(request.hasSameContent("CUST-1", "TV-100", "crm")).isFalse();
        assertThat(request.hasSameContent("CUST-1", "INTERNET-500", "outro-sistema")).isFalse();
    }
}
