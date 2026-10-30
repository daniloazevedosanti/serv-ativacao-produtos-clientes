package com.desafio.activation.infrastructure.provider;

import com.desafio.activation.config.ProviderClientConfig;
import com.desafio.activation.config.ProviderProperties;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/** Cliente HTTP contra um provedor simulado (WireMock): status, headers e timeouts reais. */
class HttpActivationProviderTest {

    @RegisterExtension
    static WireMockExtension wiremock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private static final String PATH = "/provider/v1/activate";

    /**
     * Folgado de propósito: a 1ª chamada da classe paga o aquecimento da JVM, do HttpClient e do
     * WireMock (passa de 1 s em máquinas mais lentas). Só o teste de timeout usa um prazo curto.
     */
    private static final Duration NORMAL_READ_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration SHORT_READ_TIMEOUT = Duration.ofMillis(300);

    private ActivationProvider provider;
    private ProviderActivationCommand command;

    @BeforeEach
    void setUp() {
        provider = providerFor(URI.create(wiremock.baseUrl()), NORMAL_READ_TIMEOUT, "secret-key");
        command = new ProviderActivationCommand(UUID.randomUUID(), "CUST-10001", "INTERNET-500");
    }

    private static ActivationProvider providerFor(URI baseUrl, Duration readTimeout, String apiKey) {
        var properties = new ProviderProperties(baseUrl, Duration.ofSeconds(2), readTimeout, apiKey);
        return new ProviderClientConfig().activationProvider(RestClient.builder(), properties);
    }

    @Test
    void sucesso_envia_corpo_chave_de_idempotencia_e_credencial() {
        wiremock.stubFor(post(PATH).willReturn(ok()));

        var result = provider.activate(command);

        assertThat(result).isInstanceOf(ProviderResult.Activated.class);
        wiremock.verify(postRequestedFor(urlEqualTo(PATH))
                .withHeader("Idempotency-Key", equalTo(command.requestId().toString()))
                .withHeader("Authorization", equalTo("Bearer secret-key"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson("""
                        {"requestId":"%s","customerId":"CUST-10001","productCode":"INTERNET-500"}
                        """.formatted(command.requestId()))));
    }

    @Test
    void http_400_e_erro_definitivo_com_detalhe() {
        wiremock.stubFor(post(PATH).willReturn(aResponse().withStatus(400)
                .withBody("{\"error\":\"invalid product\"}")));

        var result = provider.activate(command);

        assertThat(result).isEqualTo(new ProviderResult.Rejected(400, "{\"error\":\"invalid product\"}"));
    }

    @Test
    void http_500_e_erro_temporario() {
        wiremock.stubFor(post(PATH).willReturn(aResponse().withStatus(500)));

        var result = provider.activate(command);

        assertThat(result).isEqualTo(new ProviderResult.TemporaryFailure("PROVIDER_HTTP_500", null));
    }

    @Test
    void http_503_com_retry_after_em_segundos() {
        wiremock.stubFor(post(PATH).willReturn(aResponse().withStatus(503).withHeader("Retry-After", "30")));

        var result = provider.activate(command);

        assertThat(result).isEqualTo(new ProviderResult.TemporaryFailure("PROVIDER_HTTP_503", Duration.ofSeconds(30)));
    }

    @Test
    void http_429_com_retry_after_em_data_http() {
        String date = DateTimeFormatter.RFC_1123_DATE_TIME
                .format(ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(120));
        wiremock.stubFor(post(PATH).willReturn(aResponse().withStatus(429).withHeader("Retry-After", date)));

        var result = provider.activate(command);

        assertThat(result).isInstanceOfSatisfying(ProviderResult.TemporaryFailure.class, failure -> {
            assertThat(failure.reason()).isEqualTo("PROVIDER_HTTP_429");
            assertThat(failure.retryAfter()).isBetween(Duration.ofSeconds(100), Duration.ofSeconds(121));
        });
    }

    @Test
    void credencial_recusada_e_temporaria_para_nao_perder_ativacoes() {
        wiremock.stubFor(post(PATH).willReturn(aResponse().withStatus(401)));

        assertThat(provider.activate(command))
                .isEqualTo(new ProviderResult.TemporaryFailure("PROVIDER_AUTH_401", null));
    }

    @Test
    void timeout_de_leitura_e_temporario() {
        wiremock.stubFor(post(PATH).willReturn(ok().withFixedDelay(2_000)));
        var impatient = providerFor(URI.create(wiremock.baseUrl()), SHORT_READ_TIMEOUT, null);

        var result = impatient.activate(command);

        assertThat(result).isEqualTo(new ProviderResult.TemporaryFailure("TIMEOUT", null));
    }

    @Test
    void provedor_fora_do_ar_e_falha_de_conexao() {
        var offline = providerFor(URI.create("http://localhost:1"), NORMAL_READ_TIMEOUT, null);

        var result = offline.activate(command);

        assertThat(result).isEqualTo(new ProviderResult.TemporaryFailure("CONNECTION_FAILED", null));
    }

    @Test
    void classificacao_dos_status() {
        var headers = new HttpHeaders();
        assertThat(HttpActivationProvider.classify(HttpStatus.CREATED, headers, null))
                .isInstanceOf(ProviderResult.Activated.class);
        assertThat(HttpActivationProvider.classify(HttpStatus.UNPROCESSABLE_ENTITY, headers, null))
                .isInstanceOf(ProviderResult.Rejected.class);
        assertThat(HttpActivationProvider.classify(HttpStatus.REQUEST_TIMEOUT, headers, null))
                .isInstanceOf(ProviderResult.TemporaryFailure.class);
        assertThat(HttpActivationProvider.classify(HttpStatus.BAD_GATEWAY, headers, null))
                .isInstanceOf(ProviderResult.TemporaryFailure.class);
    }

    @Test
    void retry_after_invalido_e_ignorado() {
        assertThat(HttpActivationProvider.parseRetryAfter("amanhã")).isNull();
        assertThat(HttpActivationProvider.parseRetryAfter("-5")).isNull();
        assertThat(HttpActivationProvider.parseRetryAfter(null)).isNull();
    }
}
