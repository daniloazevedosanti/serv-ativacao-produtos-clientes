package com.desafio.activation.infrastructure.provider;

import com.desafio.activation.application.ActivationProvider;
import com.desafio.activation.application.ProviderActivationCommand;
import com.desafio.activation.application.ProviderResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * Cliente HTTP do provedor de ativação ({@code POST /provider/v1/activate}).
 *
 * <p>Classificação das respostas:
 * <ul>
 *   <li>2xx → {@link ProviderResult.Activated}</li>
 *   <li>408, 425, 429 e 5xx → {@link ProviderResult.TemporaryFailure} (respeita {@code Retry-After})</li>
 *   <li>401/403 → temporária: é problema de credencial nossa (expirou/rotacionou), não da
 *       solicitação; falhar definitivamente perderia todas as ativações até alguém corrigir</li>
 *   <li>demais 4xx (ex.: 400) → {@link ProviderResult.Rejected}: reenviar não muda nada</li>
 *   <li>timeout / falha de conexão → temporária. Atenção: no timeout de leitura o provedor
 *       <i>pode</i> ter ativado; por isso todo envio leva o header {@code Idempotency-Key}</li>
 * </ul>
 */
public class HttpActivationProvider implements ActivationProvider {

    static final String ACTIVATE_PATH = "/provider/v1/activate";
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final int MAX_DETAIL_LENGTH = 200;

    private static final Logger log = LoggerFactory.getLogger(HttpActivationProvider.class);

    private final RestClient restClient;

    public HttpActivationProvider(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public ProviderResult activate(ProviderActivationCommand command) {
        try {
            return restClient.post()
                    .uri(ACTIVATE_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header(IDEMPOTENCY_KEY_HEADER, command.idempotencyKey())
                    .body(new ActivateRequestBody(command.requestId(), command.customerId(), command.productCode()))
                    .exchange((request, response) -> classify(response.getStatusCode(),
                            response.getHeaders(), readDetail(response.getBody())), true);
        } catch (ResourceAccessException e) {
            return ioFailure(command, e);
        } catch (RestClientException e) {
            log.warn("Erro inesperado do cliente HTTP do provedor requestId={}", command.requestId(), e);
            return new ProviderResult.TemporaryFailure("CLIENT_ERROR", null);
        }
    }

    static ProviderResult classify(HttpStatusCode status, HttpHeaders headers, String detail) {
        int code = status.value();
        if (status.is2xxSuccessful()) {
            return new ProviderResult.Activated();
        }
        if (code == 408 || code == 425 || code == 429 || status.is5xxServerError()) {
            return new ProviderResult.TemporaryFailure("PROVIDER_HTTP_" + code,
                    parseRetryAfter(headers.getFirst(HttpHeaders.RETRY_AFTER)));
        }
        if (code == 401 || code == 403) {
            log.error("Provedor recusou nossa credencial (HTTP {}). Verifique a configuração do segredo.", code);
            return new ProviderResult.TemporaryFailure("PROVIDER_AUTH_" + code, null);
        }
        return new ProviderResult.Rejected(code, detail);
    }

    /** {@code Retry-After} pode vir em segundos ("120") ou como data HTTP (RFC 1123). */
    static Duration parseRetryAfter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            long seconds = Long.parseLong(trimmed);
            return seconds >= 0 ? Duration.ofSeconds(seconds) : null;
        } catch (NumberFormatException ignored) {
            // pode ser data
        }
        try {
            var date = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME);
            var delay = Duration.between(ZonedDateTime.now(date.getZone()), date);
            return delay.isNegative() ? Duration.ZERO : delay;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private ProviderResult ioFailure(ProviderActivationCommand command, ResourceAccessException e) {
        String reason;
        if (hasCause(e, HttpConnectTimeoutException.class) || hasCause(e, ConnectException.class)) {
            reason = "CONNECTION_FAILED"; // não chegou ao provedor: reenviar é seguro
        } else if (hasCause(e, HttpTimeoutException.class) || hasCause(e, SocketTimeoutException.class)) {
            reason = "TIMEOUT"; // ambíguo: o provedor pode ter ativado (idempotency key protege)
        } else {
            reason = "IO_ERROR";
        }
        log.warn("Falha de comunicação com o provedor requestId={} reason={} cause={}",
                command.requestId(), reason, e.getMostSpecificCause().toString());
        return new ProviderResult.TemporaryFailure(reason, null);
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private static String readDetail(InputStream body) {
        try (body) {
            byte[] bytes = body.readNBytes(MAX_DETAIL_LENGTH);
            return new String(bytes, StandardCharsets.UTF_8).replaceAll("\\s+", " ").trim();
        } catch (IOException e) {
            return null;
        }
    }

    record ActivateRequestBody(UUID requestId, String customerId, String productCode) {
    }
}
