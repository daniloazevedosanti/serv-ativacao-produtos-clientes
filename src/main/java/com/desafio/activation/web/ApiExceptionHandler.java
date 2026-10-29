package com.desafio.activation.web;

import com.desafio.activation.application.ActivationRequestNotFoundException;
import com.desafio.activation.application.DuplicateExternalIdException;
import com.desafio.activation.domain.InvalidStatusTransitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Converte exceções em respostas Problem Details (RFC 9457, {@code application/problem+json}).
 * Nunca devolve stack trace nem mensagens internas; erros inesperados viram uma mensagem
 * genérica com {@code traceId} para localizar o detalhe no log.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final TraceIdProvider traceIdProvider;

    public ApiExceptionHandler(TraceIdProvider traceIdProvider) {
        this.traceIdProvider = traceIdProvider;
    }

    @ExceptionHandler(ActivationRequestNotFoundException.class)
    ResponseEntity<Object> notFound(ActivationRequestNotFoundException ex, WebRequest request) {
        return problem(ex, HttpStatus.NOT_FOUND, ProblemTypes.NOT_FOUND, "Solicitação não encontrada",
                ex.getMessage(), request);
    }

    @ExceptionHandler(DuplicateExternalIdException.class)
    ResponseEntity<Object> duplicate(DuplicateExternalIdException ex, WebRequest request) {
        return problem(ex, HttpStatus.CONFLICT, ProblemTypes.DUPLICATE_EXTERNAL_ID, "Solicitação duplicada",
                ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidStatusTransitionException.class)
    ResponseEntity<Object> invalidTransition(InvalidStatusTransitionException ex, WebRequest request) {
        return problem(ex, HttpStatus.CONFLICT, ProblemTypes.CONFLICT, "Operação inválida para o status atual",
                ex.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception ex, WebRequest request) {
        log.error("Erro inesperado em {}", path(request), ex);
        return problem(ex, HttpStatus.INTERNAL_SERVER_ERROR, ProblemTypes.INTERNAL, "Erro interno",
                "Ocorreu um erro inesperado. Informe o traceId ao suporte.", request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        var errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> Map.of("field", e.getField(), "message", String.valueOf(e.getDefaultMessage())))
                .toList();
        var body = validationProblem(errors);
        return handleExceptionInternal(ex, body, headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                           HttpHeaders headers,
                                                                           HttpStatusCode status,
                                                                           WebRequest request) {
        var errors = ex.getParameterValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream().map(e -> Map.of(
                        "field", String.valueOf(r.getMethodParameter().getParameterName()),
                        "message", String.valueOf(e.getDefaultMessage()))))
                .toList();
        var body = validationProblem(errors);
        return handleExceptionInternal(ex, body, headers, HttpStatus.BAD_REQUEST, request);
    }

    /** Ex.: id que não é UUID, status inexistente no filtro, page não numérico. */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
                                                        HttpStatusCode status, WebRequest request) {
        String name = ex instanceof MethodArgumentTypeMismatchException m ? m.getName() : ex.getPropertyName();
        var body = validationProblem(List.of(Map.of("field", String.valueOf(name), "message", "valor inválido")));
        return handleExceptionInternal(ex, body, headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        if (causedByPayloadTooLarge(ex)) {
            return problem(ex, HttpStatus.PAYLOAD_TOO_LARGE, ProblemTypes.PAYLOAD_TOO_LARGE,
                    "Corpo muito grande", "O corpo da requisição excede o tamanho permitido.", request);
        }
        // Não repassamos a mensagem do Jackson: ela expõe nomes de classes internas.
        return problem(ex, HttpStatus.BAD_REQUEST, ProblemTypes.MALFORMED_REQUEST, "Requisição malformada",
                "O corpo da requisição não é um JSON válido.", request);
    }

    /** Ponto único por onde passam todas as respostas: adiciona o traceId e o "instance". */
    @Override
    protected ResponseEntity<Object> createResponseEntity(@Nullable Object body, HttpHeaders headers,
                                                          HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            if (problem.getInstance() == null) {
                problem.setInstance(URI.create(path(request)));
            }
            String traceId = traceIdProvider.currentTraceId();
            if (traceId != null) {
                problem.setProperty("traceId", traceId);
            }
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private ResponseEntity<Object> problem(Exception ex, HttpStatus status, URI type, String title, String detail,
                                           WebRequest request) {
        var body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setType(type);
        body.setTitle(title);
        return handleExceptionInternal(ex, body, new HttpHeaders(), status, request);
    }

    private static ProblemDetail validationProblem(List<Map<String, String>> errors) {
        var body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Um ou mais campos não passaram na validação.");
        body.setType(ProblemTypes.VALIDATION);
        body.setTitle("Dados inválidos");
        body.setProperty("errors", errors);
        return body;
    }

    private static boolean causedByPayloadTooLarge(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof PayloadTooLargeException) {
                return true;
            }
            if (t.getCause() == t) {
                return false;
            }
        }
        return false;
    }

    private static String path(WebRequest request) {
        return request instanceof ServletWebRequest servlet ? servlet.getRequest().getRequestURI() : "";
    }
}
