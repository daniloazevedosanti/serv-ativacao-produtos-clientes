package com.desafio.activation.integration;

import com.desafio.activation.domain.ActivationRequest;
import com.desafio.activation.infrastructure.persistence.ActivationRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Mapeamento JPA × schema Flyway, constraint de unicidade, lock otimista e consultas do worker. */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED) // cada operação no seu commit, como em produção
class ActivationRequestRepositoryIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    ActivationRequestRepository repository;

    @Autowired
    PlatformTransactionManager transactionManager;

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    private ActivationRequest newRequest(String externalId) {
        return ActivationRequest.create(externalId, "CUST-1", "INTERNET-500", "crm", now);
    }

    @Test
    void persiste_e_le_com_datas_em_utc_sem_perda_de_precisao() {
        var saved = repository.saveAndFlush(newRequest("EXT-1"));

        var loaded = repository.findByExternalId("EXT-1").orElseThrow();

        assertThat(loaded.getId()).isEqualTo(saved.getId());
        assertThat(loaded.getCreatedAt()).isEqualTo(now);
        assertThat(loaded.getVersion()).isZero();
    }

    @Test
    void banco_rejeita_external_id_duplicado() {
        repository.saveAndFlush(newRequest("EXT-DUP"));

        assertThatThrownBy(() -> repository.saveAndFlush(newRequest("EXT-DUP")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void lock_otimista_impede_que_duas_escritas_concorrentes_se_sobrescrevam() {
        var id = repository.saveAndFlush(newRequest("EXT-LOCK")).getId();
        var copyA = repository.findById(id).orElseThrow();
        var copyB = repository.findById(id).orElseThrow();

        copyA.startProcessing(now);
        repository.saveAndFlush(copyA);
        copyB.startProcessing(now);

        assertThatThrownBy(() -> repository.saveAndFlush(copyB))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void consultas_do_worker() {
        var due = repository.saveAndFlush(newRequest("EXT-DUE"));
        var future = newRequest("EXT-FUTURE");
        future.startProcessing(now);
        future.retryLater("x", now.plusSeconds(3600), now);
        repository.saveAndFlush(future);
        var stuck = newRequest("EXT-STUCK");
        stuck.startProcessing(now.minusSeconds(600));
        repository.saveAndFlush(stuck);

        assertThat(repository.findDueIds(now, PageRequest.ofSize(10))).containsExactly(due.getId());
        assertThat(repository.findStuckProcessingIds(now.minusSeconds(60), PageRequest.ofSize(10)))
                .containsExactly(stuck.getId());
        assertThat(repository.countByStatus()).hasSize(2);
    }

    @Test
    void busca_por_id_respeita_o_dono() {
        var id = repository.saveAndFlush(newRequest("EXT-OWNER")).getId();

        assertThat(repository.findByIdAndClientId(id, "crm")).isPresent();
        assertThat(repository.findByIdAndClientId(id, "billing")).isEmpty();
    }

    @Test
    void transacao_abortada_por_duplicidade_nao_impede_releitura_em_nova_transacao() {
        repository.saveAndFlush(newRequest("EXT-RETRY"));
        var tx = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> repository.saveAndFlush(newRequest("EXT-RETRY"))))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(repository.findByExternalId("EXT-RETRY")).isPresent();
    }
}
