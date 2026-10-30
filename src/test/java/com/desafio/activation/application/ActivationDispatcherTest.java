package com.desafio.activation.application;

import com.desafio.activation.domain.ActivationRequest;
import com.desafio.activation.domain.ActivationStatus;
import com.desafio.activation.infrastructure.persistence.ActivationRequestRepository;
import com.desafio.activation.infrastructure.provider.ActivationProvider;
import com.desafio.activation.infrastructure.provider.ProviderActivationCommand;
import com.desafio.activation.infrastructure.provider.ProviderResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Decisões do dispatcher para cada retorno do provedor (unitário, com Mockito). */
@ExtendWith(MockitoExtension.class)
class ActivationDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-10-26T14:00:00Z");

    @Mock
    private ActivationRequestRepository repository;
    @Mock
    private ActivationProvider provider;

    private ActivationDispatcher dispatcher;
    private ActivationRequest request;

    @BeforeEach
    void setUp() {
        var tx = new TransactionTemplate(mock(PlatformTransactionManager.class));
        var retryPolicy = new RetryPolicy(3, Duration.ofSeconds(2), 2.0, Duration.ofMinutes(1),
                Duration.ofMinutes(5), () -> 1.0);
        var time = new TimeSource(Clock.fixed(NOW, ZoneOffset.UTC));
        dispatcher = new ActivationDispatcher(repository, provider, tx, retryPolicy, time, new SimpleMeterRegistry());

        request = ActivationRequest.create("EXT-1", "CUST-1", "INTERNET-500", "crm", NOW.minusSeconds(1));
        ReflectionTestUtils.setField(request, "version", 0L);
    }

    private void givenRepositoryWorks() {
        when(repository.findById(request.getId())).thenReturn(Optional.of(request));
        when(repository.saveAndFlush(any(ActivationRequest.class))).thenAnswer(inv -> {
            ActivationRequest saved = inv.getArgument(0);
            ReflectionTestUtils.setField(saved, "version", saved.getVersion() + 1);
            return saved;
        });
    }

    @Test
    void sucesso_do_provedor_conclui_a_solicitacao() {
        givenRepositoryWorks();
        when(provider.activate(any())).thenReturn(new ProviderResult.Activated());

        dispatcher.dispatch(request.getId());

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.COMPLETED);
        assertThat(request.getAttempts()).isEqualTo(1);
    }

    @Test
    void envia_o_id_da_solicitacao_como_chave_de_idempotencia() {
        givenRepositoryWorks();
        when(provider.activate(any())).thenAnswer(inv -> {
            ProviderActivationCommand command = inv.getArgument(0);
            assertThat(command.idempotencyKey()).isEqualTo(request.getId().toString());
            assertThat(command.productCode()).isEqualTo("INTERNET-500");
            return new ProviderResult.Activated();
        });

        dispatcher.dispatch(request.getId());

        verify(provider).activate(any());
    }

    @Test
    void erro_definitivo_marca_falha_sem_nova_tentativa() {
        givenRepositoryWorks();
        when(provider.activate(any())).thenReturn(new ProviderResult.Rejected(400, "productCode desconhecido"));

        dispatcher.dispatch(request.getId());

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.FAILED);
        assertThat(request.getFailureReason()).isEqualTo("PROVIDER_REJECTED: HTTP 400 - productCode desconhecido");
        assertThat(request.getNextAttemptAt()).isNull();
    }

    @Test
    void erro_temporario_agenda_nova_tentativa_com_backoff() {
        givenRepositoryWorks();
        when(provider.activate(any())).thenReturn(new ProviderResult.TemporaryFailure("PROVIDER_HTTP_500", null));

        dispatcher.dispatch(request.getId());

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(request.getFailureReason()).isEqualTo("PROVIDER_HTTP_500");
        assertThat(request.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(2));
    }

    @Test
    void timeout_e_tratado_como_temporario() {
        givenRepositoryWorks();
        when(provider.activate(any())).thenReturn(new ProviderResult.TemporaryFailure("TIMEOUT", null));

        dispatcher.dispatch(request.getId());

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(request.getFailureReason()).isEqualTo("TIMEOUT");
    }

    @Test
    void retry_after_do_provedor_define_a_proxima_tentativa() {
        givenRepositoryWorks();
        when(provider.activate(any()))
                .thenReturn(new ProviderResult.TemporaryFailure("PROVIDER_HTTP_503", Duration.ofSeconds(40)));

        dispatcher.dispatch(request.getId());

        assertThat(request.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(40));
    }

    @Test
    void esgotadas_as_tentativas_marca_falha() {
        ReflectionTestUtils.setField(request, "attempts", 2); // a próxima é a 3ª e última
        givenRepositoryWorks();
        when(provider.activate(any())).thenReturn(new ProviderResult.TemporaryFailure("PROVIDER_HTTP_500", null));

        dispatcher.dispatch(request.getId());

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.FAILED);
        assertThat(request.getAttempts()).isEqualTo(3);
        assertThat(request.getFailureReason()).isEqualTo("MAX_ATTEMPTS_EXCEEDED: PROVIDER_HTTP_500");
    }

    @Test
    void solicitacao_ainda_nao_vencida_nao_e_enviada() {
        request.startProcessing(NOW);
        request.retryLater("x", NOW.plusSeconds(60), NOW);
        when(repository.findById(request.getId())).thenReturn(Optional.of(request));

        dispatcher.dispatch(request.getId());

        verify(provider, never()).activate(any());
    }

    @Test
    void outro_worker_venceu_a_disputa_nao_chama_o_provedor() {
        when(repository.findById(request.getId())).thenReturn(Optional.of(request));
        when(repository.saveAndFlush(any(ActivationRequest.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(ActivationRequest.class, request.getId()));

        dispatcher.dispatch(request.getId());

        verify(provider, never()).activate(any());
    }

    @Test
    void erro_inesperado_nao_derruba_o_worker_e_deixa_para_reconciliacao() {
        givenRepositoryWorks();
        when(provider.activate(any())).thenThrow(new IllegalStateException("bug"));

        dispatcher.dispatch(request.getId());

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PROCESSING);
    }

    @Test
    void resultado_obsoleto_e_descartado() {
        givenRepositoryWorks();
        when(provider.activate(any())).thenAnswer(inv -> {
            // enquanto chamávamos o provedor, o reconciliador mexeu na solicitação
            ReflectionTestUtils.setField(request, "version", 99L);
            return new ProviderResult.Activated();
        });

        dispatcher.dispatch(request.getId());

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PROCESSING);
    }

    @Test
    void reconciliacao_devolve_solicitacao_presa_para_a_fila() {
        givenRepositoryWorks();
        request.startProcessing(NOW.minusSeconds(120));

        dispatcher.releaseIfStuck(request.getId(), NOW.minusSeconds(60));

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(request.getFailureReason()).isEqualTo("LEASE_EXPIRED");
        assertThat(request.isDue(NOW)).isTrue();
    }

    @Test
    void reconciliacao_ignora_solicitacao_que_nao_esta_presa() {
        when(repository.findById(request.getId())).thenReturn(Optional.of(request));
        request.startProcessing(NOW.minusSeconds(10));

        dispatcher.releaseIfStuck(request.getId(), NOW.minusSeconds(60));

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PROCESSING);
        verify(repository, never()).saveAndFlush(any());
    }
}
