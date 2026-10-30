package com.desafio.activation.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    static final String BEARER_SCHEME = "bearer-jwt";

    @Bean
    OpenAPI activationOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Activation Service API")
                        .version("v1")
                        .description("""
                                Registro e consulta de solicitações de ativação de produtos. A ativação no \
                                provedor externo é assíncrona: o POST responde com status PENDING e o resultado \
                                (COMPLETED ou FAILED) é acompanhado pelo GET. Erros seguem Problem Details \
                                (RFC 9457) com traceId."""))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Access token OAuth 2.0 (client credentials) com escopos activation:read "
                                + "e/ou activation:write. Em desenvolvimento: gere com scripts/dev-token.sh "
                                + "(ou .ps1).")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
