package com.desafio.activation.web.filter;

import com.desafio.activation.web.TraceIdProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;

/** Escreve Problem Details em respostas geradas fora do Spring MVC (filtros, Spring Security). */
@Component
public class ProblemResponseWriter {

    private final ObjectMapper objectMapper;
    private final TraceIdProvider traceIdProvider;

    public ProblemResponseWriter(ObjectMapper objectMapper, TraceIdProvider traceIdProvider) {
        this.objectMapper = objectMapper;
        this.traceIdProvider = traceIdProvider;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, URI type,
                      String title, String detail) throws IOException {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(type);
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        String traceId = traceIdProvider.currentTraceId();
        if (traceId != null) {
            problem.setProperty("traceId", traceId);
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
