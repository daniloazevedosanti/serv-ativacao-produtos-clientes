package com.desafio.activation.integration;

import org.junit.jupiter.api.RepeatedTest;
import org.springframework.http.HttpStatusCode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Várias requisições simultâneas com o mesmo externalId, contra o PostgreSQL real.
 * Prova que a unicidade não depende do "consulta e depois insere" (que tem corrida).
 */
class ConcurrentDuplicateIT extends AbstractIntegrationIT {

    private static final int PARALLEL_REQUESTS = 20;

    private List<Integer> fireConcurrently(String externalId, boolean sameContent) throws Exception {
        PROVIDER.stubFor(post("/provider/v1/activate").willReturn(ok()));
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(PARALLEL_REQUESTS)) {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < PARALLEL_REQUESTS; i++) {
                String product = sameContent ? "INTERNET-500" : "PRODUCT-" + i;
                futures.add(executor.submit(() -> {
                    start.await(); // todas partem juntas
                    HttpStatusCode status = client("crm").post().uri("/api/v1/requests")
                            .body(body(externalId, "CUST-10001", product))
                            .retrieve().toBodilessEntity().getStatusCode();
                    return status.value();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : futures) {
                statuses.add(f.get());
            }
            return statuses;
        }
    }

    private int rowsWith(String externalId) {
        return jdbc.queryForObject("select count(*) from activation_request where external_id = ?",
                Integer.class, externalId);
    }

    @RepeatedTest(3)
    void retries_simultaneos_criam_um_unico_registro() throws Exception {
        var statuses = fireConcurrently("EXT-RACE", true);

        assertThat(rowsWith("EXT-RACE")).isEqualTo(1);
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 200).hasSize(PARALLEL_REQUESTS - 1);
    }

    @RepeatedTest(3)
    void conteudos_diferentes_simultaneos_criam_um_registro_e_os_demais_recebem_409() throws Exception {
        var statuses = fireConcurrently("EXT-RACE-CONFLICT", false);

        assertThat(rowsWith("EXT-RACE-CONFLICT")).isEqualTo(1);
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(PARALLEL_REQUESTS - 1);
    }
}
