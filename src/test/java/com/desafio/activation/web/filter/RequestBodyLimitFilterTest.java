package com.desafio.activation.web.filter;

import com.desafio.activation.web.PayloadTooLargeException;
import com.desafio.activation.web.TraceIdProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestBodyLimitFilterTest {

    private final RequestBodyLimitFilter filter = new RequestBodyLimitFilter(10, new ProblemResponseWriter(
            new ObjectMapper(),
            new TraceIdProvider(new StaticListableBeanFactory().getBeanProvider(io.micrometer.tracing.Tracer.class))));

    @Test
    void rejeita_pelo_content_length_sem_ler_o_corpo() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/requests");
        request.setContent("12345678901".getBytes(StandardCharsets.UTF_8));
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            throw new AssertionError("não deveria chegar ao controller");
        });

        assertThat(response.getStatus()).isEqualTo(413);
    }

    @Test
    void corpo_em_chunks_sem_content_length_e_interrompido_ao_passar_do_limite() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/requests") {
            @Override
            public long getContentLengthLong() {
                return -1; // Transfer-Encoding: chunked
            }
        };
        request.setContent("123456789012345".getBytes(StandardCharsets.UTF_8));

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
                assertThatThrownBy(() -> req.getInputStream().readAllBytes())
                        .isInstanceOf(PayloadTooLargeException.class));
    }

    @Test
    void corpo_dentro_do_limite_passa_intacto() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/requests");
        request.setContent("123".getBytes(StandardCharsets.UTF_8));
        var read = new StringBuilder();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
                read.append(new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8)));

        assertThat(read).hasToString("123");
    }
}
