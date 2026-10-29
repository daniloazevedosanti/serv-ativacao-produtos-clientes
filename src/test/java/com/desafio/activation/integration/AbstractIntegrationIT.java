package com.desafio.activation.integration;

import com.desafio.activation.support.TestJwt;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * Aplicação completa (porta aleatória) contra PostgreSQL real (Testcontainers) e provedor
 * simulado (WireMock). Containers/servidores são compartilhados entre as classes de teste
 * (padrão singleton) para manter a suíte rápida.
 */
@AutoConfigureObservability // métricas e tracing (desligados por padrão nos testes do Spring Boot)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
abstract class AbstractIntegrationIT {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static final WireMockServer PROVIDER = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        POSTGRES.start();
        PROVIDER.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("activation.provider.base-url", PROVIDER::baseUrl);
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void resetState() {
        PROVIDER.resetAll();
        jdbc.update("delete from activation_request");
    }

    RestClient client(String clientId) {
        return clientWithBearer(TestJwt.token().clientId(clientId).bearer());
    }

    RestClient client(String clientId, String scope) {
        return clientWithBearer(TestJwt.token().clientId(clientId).scope(scope).bearer());
    }

    private RestClient clientWithBearer(String bearer) {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader(HttpHeaders.AUTHORIZATION, bearer)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultStatusHandler(status -> true, (request, response) -> {
                    // não lança exceção: os testes verificam status e corpo
                })
                .build();
    }

    static String body(String externalId, String customerId, String productCode) {
        return """
                {"externalId":"%s","customerId":"%s","productCode":"%s"}
                """.formatted(externalId, customerId, productCode);
    }
}
