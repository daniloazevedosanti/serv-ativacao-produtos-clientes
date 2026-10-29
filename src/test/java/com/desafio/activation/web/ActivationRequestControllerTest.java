package com.desafio.activation.web;

import com.desafio.activation.application.ActivationRequestFilter;
import com.desafio.activation.application.ActivationRequestNotFoundException;
import com.desafio.activation.application.ActivationRequestService;
import com.desafio.activation.application.CreateActivationCommand;
import com.desafio.activation.application.CreationResult;
import com.desafio.activation.application.DuplicateExternalIdException;
import com.desafio.activation.config.SecurityConfig;
import com.desafio.activation.config.WebFilterConfig;
import com.desafio.activation.domain.ActivationRequest;
import com.desafio.activation.domain.ActivationStatus;
import com.desafio.activation.support.TestJwt;
import com.desafio.activation.web.filter.ProblemResponseWriter;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Camada web (controller + validação + erros + segurança) com o serviço simulado.
 * Os tokens são JWT assinados de verdade: a validação de assinatura/emissor/audiência é a real.
 */
@WebMvcTest(ActivationRequestController.class)
@Import({SecurityConfig.class, WebFilterConfig.class, TraceIdProvider.class, ProblemResponseWriter.class})
@ActiveProfiles("test")
class ActivationRequestControllerTest {

    private static final String VALID_BODY = """
            {"externalId":"EXT-12345","customerId":"CUST-10001","productCode":"INTERNET-500"}
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ActivationRequestService service;

    private static ActivationRequest sample() {
        return ActivationRequest.create("EXT-12345", "CUST-10001", "INTERNET-500", "crm-system",
                Instant.parse("2026-10-26T14:03:11Z"));
    }

    private static MockHttpServletRequestBuilder postRequest(String body) {
        return post("/api/v1/requests")
                .header(HttpHeaders.AUTHORIZATION, TestJwt.token().bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    @Nested
    class Criacao {

        @Test
        void cria_e_responde_201_com_location() throws Exception {
            var request = sample();
            when(service.create(any())).thenReturn(new CreationResult.Created(request));

            mvc.perform(postRequest(VALID_BODY))
                    .andExpect(status().isCreated())
                    .andExpect(header().string(HttpHeaders.LOCATION, endsWith("/api/v1/requests/" + request.getId())))
                    .andExpect(jsonPath("$.id").value(request.getId().toString()))
                    .andExpect(jsonPath("$.externalId").value("EXT-12345"))
                    .andExpect(jsonPath("$.customerId").value("CUST-10001"))
                    .andExpect(jsonPath("$.productCode").value("INTERNET-500"))
                    .andExpect(jsonPath("$.status").value("PENDING"))
                    .andExpect(jsonPath("$.createdAt").value("2026-10-26T14:03:11Z"))
                    .andExpect(jsonPath("$.updatedAt").value("2026-10-26T14:03:11Z"))
                    .andExpect(jsonPath("$.failureReason").doesNotExist());

            verify(service).create(new CreateActivationCommand("EXT-12345", "CUST-10001", "INTERNET-500",
                    "crm-system"));
        }

        @Test
        void repeticao_idempotente_responde_200_com_o_registro_existente() throws Exception {
            var request = sample();
            when(service.create(any())).thenReturn(new CreationResult.Replayed(request));

            mvc.perform(postRequest(VALID_BODY))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Idempotent-Replayed", "true"))
                    .andExpect(header().exists(HttpHeaders.LOCATION))
                    .andExpect(jsonPath("$.id").value(request.getId().toString()));
        }

        @Test
        void mesmo_external_id_com_dados_diferentes_responde_409() throws Exception {
            when(service.create(any())).thenThrow(new DuplicateExternalIdException("EXT-12345"));

            mvc.perform(postRequest(VALID_BODY))
                    .andExpect(status().isConflict())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("https://api.exemplo.com/problems/duplicate-external-id"))
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.instance").value("/api/v1/requests"));
        }

        @Test
        void dados_invalidos_respondem_400_com_lista_de_erros() throws Exception {
            mvc.perform(postRequest("""
                            {"externalId":"EXT 1","productCode":"%s"}
                            """.formatted("X".repeat(65))))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("https://api.exemplo.com/problems/validation-error"))
                    .andExpect(jsonPath("$.title").value("Dados inválidos"))
                    .andExpect(jsonPath("$.errors[?(@.field=='customerId')].message").value("é obrigatório"))
                    .andExpect(jsonPath("$.errors[?(@.field=='externalId')]").exists())
                    .andExpect(jsonPath("$.errors[?(@.field=='productCode')]").exists());

            verify(service, never()).create(any());
        }

        @Test
        void json_malformado_responde_400_sem_detalhes_internos() throws Exception {
            mvc.perform(postRequest("{\"externalId\": "))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("https://api.exemplo.com/problems/malformed-request"))
                    .andExpect(content().string(not(containsString("com.desafio"))))
                    .andExpect(content().string(not(containsString("jackson"))));
        }

        @Test
        void corpo_acima_do_limite_responde_413() throws Exception {
            String huge = "{\"externalId\":\"" + "A".repeat(20_000) + "\"}";

            mvc.perform(postRequest(huge))
                    .andExpect(status().isPayloadTooLarge())
                    .andExpect(jsonPath("$.type").value("https://api.exemplo.com/problems/payload-too-large"));
        }

        @Test
        void erro_inesperado_responde_500_generico_sem_stack_trace() throws Exception {
            when(service.create(any())).thenThrow(new IllegalStateException("senha do banco: 123"));

            mvc.perform(postRequest(VALID_BODY))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.type").value("https://api.exemplo.com/problems/internal-error"))
                    .andExpect(content().string(not(containsString("senha"))))
                    .andExpect(content().string(not(containsString("IllegalStateException"))))
                    .andExpect(content().string(not(containsString("at com."))));
        }
    }

    @Nested
    class Consulta {

        @Test
        void retorna_a_solicitacao_do_proprio_consumidor() throws Exception {
            var request = sample();
            when(service.get(request.getId(), "crm-system")).thenReturn(request);

            mvc.perform(get("/api/v1/requests/{id}", request.getId())
                            .header(HttpHeaders.AUTHORIZATION, TestJwt.token().scope("activation:read").bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PENDING"));
        }

        @Test
        void inexistente_responde_404() throws Exception {
            var id = UUID.randomUUID();
            when(service.get(id, "crm-system")).thenThrow(new ActivationRequestNotFoundException(id));

            mvc.perform(get("/api/v1/requests/{id}", id)
                            .header(HttpHeaders.AUTHORIZATION, TestJwt.token().bearer()))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("https://api.exemplo.com/problems/not-found"));
        }

        @Test
        void id_que_nao_e_uuid_responde_400() throws Exception {
            mvc.perform(get("/api/v1/requests/abc").header(HttpHeaders.AUTHORIZATION, TestJwt.token().bearer()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("id"));
        }

        @Test
        void listagem_paginada_com_filtros() throws Exception {
            var request = sample();
            when(service.list(eq(new ActivationRequestFilter(ActivationStatus.PENDING, "CUST-10001")),
                    eq("crm-system"), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(request), PageRequest.of(0, 10), 1));

            mvc.perform(get("/api/v1/requests?status=PENDING&customerId=CUST-10001&page=0&size=10")
                            .header(HttpHeaders.AUTHORIZATION, TestJwt.token().bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(1)))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.size").value(10));
        }

        @Test
        void tamanho_de_pagina_acima_do_limite_responde_400() throws Exception {
            mvc.perform(get("/api/v1/requests?size=500").header(HttpHeaders.AUTHORIZATION, TestJwt.token().bearer()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("size"));
        }

        @Test
        void status_invalido_no_filtro_responde_400() throws Exception {
            mvc.perform(get("/api/v1/requests?status=DONE").header(HttpHeaders.AUTHORIZATION, TestJwt.token().bearer()))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    class Seguranca {

        @Test
        void sem_token_responde_401() throws Exception {
            mvc.perform(post("/api/v1/requests").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("Bearer")))
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("https://api.exemplo.com/problems/unauthorized"));
            verify(service, never()).create(any());
        }

        @Test
        void escopo_somente_leitura_nao_pode_criar_403() throws Exception {
            mvc.perform(post("/api/v1/requests")
                            .header(HttpHeaders.AUTHORIZATION, TestJwt.token().scope("activation:read").bearer())
                            .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value("https://api.exemplo.com/problems/forbidden"));
            verify(service, never()).create(any());
        }

        @Test
        void escopo_somente_escrita_nao_pode_consultar_403() throws Exception {
            mvc.perform(get("/api/v1/requests/{id}", UUID.randomUUID())
                            .header(HttpHeaders.AUTHORIZATION, TestJwt.token().scope("activation:write").bearer()))
                    .andExpect(status().isForbidden());
        }

        @Test
        void token_emitido_para_outra_api_responde_401() throws Exception {
            mvc.perform(get("/api/v1/requests/{id}", UUID.randomUUID())
                            .header(HttpHeaders.AUTHORIZATION, TestJwt.token().audience("billing-api").bearer()))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void token_de_outro_emissor_responde_401() throws Exception {
            mvc.perform(get("/api/v1/requests/{id}", UUID.randomUUID())
                            .header(HttpHeaders.AUTHORIZATION, TestJwt.token().issuer("https://evil.test").bearer()))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void token_expirado_responde_401() throws Exception {
            mvc.perform(get("/api/v1/requests/{id}", UUID.randomUUID())
                            .header(HttpHeaders.AUTHORIZATION,
                                    TestJwt.token().expiresAt(Instant.now().minusSeconds(3600)).bearer()))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void token_com_assinatura_invalida_responde_401() throws Exception {
            mvc.perform(get("/api/v1/requests/{id}", UUID.randomUUID())
                            .header(HttpHeaders.AUTHORIZATION,
                                    TestJwt.token().secret("another-secret-with-at-least-32-bytes!!").bearer()))
                    .andExpect(status().isUnauthorized());
        }
    }
}
