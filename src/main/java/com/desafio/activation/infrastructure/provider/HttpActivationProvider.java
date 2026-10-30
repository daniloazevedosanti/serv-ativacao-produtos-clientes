package com.desafio.activation.infrastructure.provider;

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
import java.util.Set;
import java.util.UUID;

public class HttpActivationProvider implements ActivationProvider {

    static final String ACTIVATE_PATH = "/provider/v1/activate";
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private static final int MAX_DETAIL_LENGTH = 200;
    private static final Set<Integer> RETRYABLE_CLIENT_ERRORS = Set.of(408, 425, 429);

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
                    .exchange((request, response) -> classify(response.getStatusCode(), response.getHeaders(),
                            response.getBody()), true);
        } catch (ResourceAccessException e) {
            return communicationFailure(command, e);
        } catch (RestClientException e) {
            log.warn("Erro inesperado no cliente HTTP do provedor requestId={}", command.requestId(), e);
            return new ProviderResult.TemporaryFailure("CLIENT_ERROR", null);
        }
    }

    static ProviderResult classify(HttpStatusCode status, HttpHeaders headers, InputStream body) {
        if (status.is2xxSuccessful()) {
            return new ProviderResult.Activated();
        }
        int code = status.value();
        if (status.is5xxServerError() || RETRYABLE_CLIENT_ERRORS.contains(code)) {
            return new ProviderResult.TemporaryFailure("PROVIDER_HTTP_" + code,
                    parseRetryAfter(headers.getFirst(HttpHeaders.RETRY_AFTER)));
        }
        if (code == 401 || code == 403) {
            // Credencial nossa expirada ou rotacionada. Falhar a solicitação de vez perderia todas as
            // ativações até alguém corrigir o segredo; melhor tentar de novo e alertar pelo log.
            log.error("Provedor recusou nossa credencial (HTTP {}). Verifique o segredo configurado.", code);
            return new ProviderResult.TemporaryFailure("PROVIDER_AUTH_" + code, null);
        }
        return new ProviderResult.Rejected(code, readDetail(body));
    }

    static Duration parseRetryAfter(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return null;
        }
        String value = headerValue.trim();
        try {
            long seconds = Long.parseLong(value);
            return seconds >= 0 ? Duration.ofSeconds(seconds) : null;
        } catch (NumberFormatException notSeconds) {
            return parseHttpDate(value);
        }
    }

    private static Duration parseHttpDate(String value) {
        try {
            var retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME);
            var delay = Duration.between(ZonedDateTime.now(retryAt.getZone()), retryAt);
            return delay.isNegative() ? Duration.ZERO : delay;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private ProviderResult communicationFailure(ProviderActivationCommand command, ResourceAccessException e) {
        String reason;
        if (causedBy(e, HttpConnectTimeoutException.class) || causedBy(e, ConnectException.class)) {
            reason = "CONNECTION_FAILED";
        } else if (causedBy(e, HttpTimeoutException.class) || causedBy(e, SocketTimeoutException.class)) {
            // Ambíguo: o provedor pode ter ativado. A Idempotency-Key é o que torna o reenvio seguro.
            reason = "TIMEOUT";
        } else {
            reason = "IO_ERROR";
        }
        log.warn("Falha de comunicação com o provedor requestId={} reason={} cause={}",
                command.requestId(), reason, e.getMostSpecificCause().toString());
        return new ProviderResult.TemporaryFailure(reason, null);
    }

    private static boolean causedBy(Throwable error, Class<? extends Throwable> type) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
            if (cause.getCause() == cause) {
                return false;
            }
        }
        return false;
    }

    private static String readDetail(InputStream body) {
        if (body == null) {
            return null;
        }
        try (body) {
            byte[] prefix = body.readNBytes(MAX_DETAIL_LENGTH);
            return new String(prefix, StandardCharsets.UTF_8).replaceAll("\\s+", " ").trim();
        } catch (IOException e) {
            return null;
        }
    }

    record ActivateRequestBody(UUID requestId, String customerId, String productCode) {
    }
}
