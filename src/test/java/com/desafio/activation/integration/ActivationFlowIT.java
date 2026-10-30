package com.desafio.activation.integration;

import com.desafio.activation.application.ActivationScheduler;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Fluxo ponta a ponta: API → banco → envio assíncrono ao provedor → status final. */
class ActivationFlowIT extends AbstractIntegrationIT {

    private static final String PATH = "/provider/v1/activate";

    @Autowired
    ActivationScheduler scheduler;

    private ResponseEntity<JsonNode> create(String externalId, String productCode) {
        return client("crm").post().uri("/api/v1/requests")
                .body(body(externalId, "CUST-10001", productCode))
                .retrieve().toEntity(JsonNode.class);
    }

    private JsonNode awaitStatus(String id, String expectedStatus) {
        return await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100)).until(
                () -> client("crm").get().uri("/api/v1/requests/{id}", id).retrieve().body(JsonNode.class),
                node -> expectedStatus.equals(node.get("status").asText()));
    }

    @Test
    void solicitacao_criada_e_ativada_pelo_provedor() {
        PROVIDER.stubFor(post(PATH).willReturn(ok()));

        var created = create("EXT-1", "INTERNET-500");

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var id = created.getBody().get("id").asText();
        assertThat(created.getHeaders().getLocation()).hasPath("/api/v1/requests/" + id);
        assertThat(created.getBody().get("status").asText()).isEqualTo("PENDING");
        assertThat(created.getBody().get("createdAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}T.*Z");
        assertThat(created.getHeaders().getFirst("X-Trace-Id")).isNotBlank();

        var completed = awaitStatus(id, "COMPLETED");

        assertThat(completed.get("attempts").asInt()).isEqualTo(1);
        assertThat(completed.get("createdAt").asText()).isEqualTo(created.getBody().get("createdAt").asText());
        PROVIDER.verify(postRequestedFor(urlEqualTo(PATH))
                .withHeader("Idempotency-Key", equalTo(id))
                .withRequestBody(equalToJson("""
                        {"requestId":"%s","customerId":"CUST-10001","productCode":"INTERNET-500"}
                        """.formatted(id))));
    }

    @Test
    void erro_400_do_provedor_marca_falha_definitiva_sem_retentar() {
        PROVIDER.stubFor(post(PATH).willReturn(aResponse().withStatus(400).withBody("produto inválido")));

        var id = create("EXT-2", "UNKNOWN").getBody().get("id").asText();

        var failed = awaitStatus(id, "FAILED");
        assertThat(failed.get("failureReason").asText()).isEqualTo("PROVIDER_REJECTED: HTTP 400 - produto inválido");
        assertThat(failed.get("attempts").asInt()).isEqualTo(1);
        PROVIDER.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void erro_500_e_retentado_ate_o_sucesso_com_a_mesma_chave_de_idempotencia() {
        PROVIDER.stubFor(post(PATH).inScenario("instavel").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(500)).willSetStateTo("recuperado"));
        PROVIDER.stubFor(post(PATH).inScenario("instavel").whenScenarioStateIs("recuperado")
                .willReturn(ok()));

        var id = create("EXT-3", "INTERNET-500").getBody().get("id").asText();

        var completed = awaitStatus(id, "COMPLETED");
        assertThat(completed.get("attempts").asInt()).isEqualTo(2);
        PROVIDER.verify(2, postRequestedFor(urlEqualTo(PATH)).withHeader("Idempotency-Key", equalTo(id)));
    }

    @Test
    void timeout_e_retentado_sem_duplicar_ativacao() {
        // 1ª chamada demora mais que o read-timeout (3s nos testes); 2ª responde rápido
        PROVIDER.stubFor(post(PATH).inScenario("lento").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(ok().withFixedDelay(5_000)).willSetStateTo("normal"));
        PROVIDER.stubFor(post(PATH).inScenario("lento").whenScenarioStateIs("normal").willReturn(ok()));

        var id = create("EXT-4", "INTERNET-500").getBody().get("id").asText();

        awaitStatus(id, "COMPLETED");
        // toda (re)tentativa leva a MESMA chave: o provedor consegue deduplicar a ativação
        var calls = PROVIDER.findAll(postRequestedFor(urlEqualTo(PATH)));
        assertThat(calls).hasSizeGreaterThanOrEqualTo(2)
                .allSatisfy(call -> assertThat(call.getHeader("Idempotency-Key")).isEqualTo(id));
    }

    @Test
    void falhas_temporarias_esgotam_as_tentativas_e_marcam_falha() {
        PROVIDER.stubFor(post(PATH).willReturn(aResponse().withStatus(503)));

        var id = create("EXT-5", "INTERNET-500").getBody().get("id").asText();

        var failed = awaitStatus(id, "FAILED");
        assertThat(failed.get("attempts").asInt()).isEqualTo(5);
        assertThat(failed.get("failureReason").asText()).isEqualTo("MAX_ATTEMPTS_EXCEEDED: PROVIDER_HTTP_503");
    }

    @Test
    void solicitacao_presa_em_processing_e_reconciliada_e_concluida() {
        PROVIDER.stubFor(post(PATH).withRequestBody(matchingJsonPath("$.productCode", equalTo("TV-100")))
                .willReturn(ok()));
        // simula uma instância que caiu durante a chamada ao provedor
        var id = UUID.randomUUID();
        var old = Timestamp.from(Instant.now().minus(Duration.ofMinutes(10)));
        jdbc.update("""
                insert into activation_request (id, external_id, customer_id, product_code, client_id, status,
                    attempts, created_at, updated_at, version)
                values (?, 'EXT-STUCK', 'CUST-1', 'TV-100', 'crm', 'PROCESSING', 1, ?, ?, 1)
                """, id, old, old);

        scheduler.reconcileStuck();

        var completed = awaitStatus(id.toString(), "COMPLETED");
        assertThat(completed.get("attempts").asInt()).isEqualTo(2);
    }

    @Test
    void consumidor_nao_ve_solicitacoes_de_outro_consumidor() {
        PROVIDER.stubFor(post(PATH).willReturn(ok()));
        var id = create("EXT-6", "INTERNET-500").getBody().get("id").asText();

        var otherGet = client("billing").get().uri("/api/v1/requests/{id}", id).retrieve().toEntity(JsonNode.class);
        var otherList = client("billing").get().uri("/api/v1/requests").retrieve().body(JsonNode.class);
        var ownList = client("crm").get().uri("/api/v1/requests").retrieve().body(JsonNode.class);

        assertThat(otherGet.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(otherList.get("totalElements").asLong()).isZero();
        assertThat(ownList.get("totalElements").asLong()).isEqualTo(1);
    }

    @Test
    void outro_consumidor_com_mesmo_external_id_recebe_409_sem_vazar_dados() {
        PROVIDER.stubFor(post(PATH).willReturn(ok()));
        create("EXT-7", "INTERNET-500");

        var response = client("billing").post().uri("/api/v1/requests")
                .body(body("EXT-7", "CUST-10001", "INTERNET-500"))
                .retrieve().toEntity(JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().has("id")).isFalse();
    }

    @Test
    void listagem_filtra_por_status_e_cliente_com_paginacao() {
        PROVIDER.stubFor(post(PATH).withRequestBody(matchingJsonPath("$.productCode", equalTo("BAD")))
                .willReturn(aResponse().withStatus(400)));
        PROVIDER.stubFor(post(PATH).withRequestBody(matchingJsonPath("$.productCode", equalTo("GOOD")))
                .willReturn(ok()));
        var ok1 = create("EXT-L1", "GOOD").getBody().get("id").asText();
        var ok2 = create("EXT-L2", "GOOD").getBody().get("id").asText();
        var bad = create("EXT-L3", "BAD").getBody().get("id").asText();
        awaitStatus(ok1, "COMPLETED");
        awaitStatus(ok2, "COMPLETED");
        awaitStatus(bad, "FAILED");

        var completed = client("crm").get().uri("/api/v1/requests?status=COMPLETED&size=1&page=0")
                .retrieve().body(JsonNode.class);
        var failed = client("crm").get().uri("/api/v1/requests?status=FAILED&customerId=CUST-10001")
                .retrieve().body(JsonNode.class);

        assertThat(completed.get("totalElements").asLong()).isEqualTo(2);
        assertThat(completed.get("totalPages").asInt()).isEqualTo(2);
        assertThat(completed.get("content")).hasSize(1);
        assertThat(failed.get("content").get(0).get("id").asText()).isEqualTo(bad);
    }

    @Test
    void sem_token_e_com_escopo_insuficiente() {
        var anonymous = org.springframework.web.client.RestClient.create("http://localhost:" + port)
                .get().uri("/api/v1/requests").exchange((req, res) -> res.getStatusCode());
        var readOnly = client("crm", "activation:read").post().uri("/api/v1/requests")
                .body(body("EXT-8", "C", "P")).retrieve().toBodilessEntity();

        assertThat(anonymous).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(readOnly.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void health_de_liveness_e_readiness_disponiveis_sem_autenticacao() {
        var anonymous = org.springframework.web.client.RestClient.create("http://localhost:" + port);

        var liveness = anonymous.get().uri("/actuator/health/liveness").exchange((req, res) -> res.getStatusCode());
        var readiness = anonymous.get().uri("/actuator/health/readiness").exchange((req, res) -> res.getStatusCode());
        var prometheus = anonymous.get().uri("/actuator/prometheus").header(HttpHeaders.ACCEPT, "text/plain")
                .exchange((req, res) -> new String(res.getBody().readAllBytes()));

        assertThat(liveness).isEqualTo(HttpStatus.OK);
        assertThat(readiness).isEqualTo(HttpStatus.OK);
        assertThat(prometheus).contains("activation_requests_creation_total");
    }
}
