package com.desafio.activation.config;

import com.desafio.activation.web.ProblemTypes;
import com.desafio.activation.web.filter.ProblemResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/**
 * 401/403 no mesmo formato Problem Details da API. Mantém o header {@code WWW-Authenticate}
 * padrão (RFC 6750), delegando aos handlers do Spring Security antes de escrever o corpo.
 */
public class ProblemDetailsSecurityHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final BearerTokenAuthenticationEntryPoint entryPoint = new BearerTokenAuthenticationEntryPoint();
    private final BearerTokenAccessDeniedHandler deniedHandler = new BearerTokenAccessDeniedHandler();
    private final ProblemResponseWriter writer;

    public ProblemDetailsSecurityHandler(ProblemResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        entryPoint.commence(request, response, authException);
        writer.write(request, response, HttpStatus.UNAUTHORIZED, ProblemTypes.UNAUTHORIZED, "Não autenticado",
                "Token de acesso ausente, inválido ou expirado.");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        deniedHandler.handle(request, response, accessDeniedException);
        writer.write(request, response, HttpStatus.FORBIDDEN, ProblemTypes.FORBIDDEN, "Acesso negado",
                "O token não possui o escopo necessário para esta operação.");
    }
}
