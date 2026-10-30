package com.desafio.activation.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Corpo do {@code POST /api/v1/requests}.
 *
 * <p>Regras: obrigatórios, até 64 caracteres, começam com letra/dígito e aceitam apenas
 * letras, dígitos e {@code . _ : -} (sem espaços). Ex.: {@code EXT-12345}, {@code INTERNET-500}.
 */
public record CreateActivationRequest(
        @Schema(description = "Identificador da solicitação no sistema de origem (chave de idempotência)",
                example = "EXT-12345", maxLength = 64)
        @NotBlank(message = "é obrigatório")
        @Size(max = 64, message = "deve ter no máximo 64 caracteres")
        @Pattern(regexp = IDENTIFIER, message = IDENTIFIER_MESSAGE)
        String externalId,

        @Schema(description = "Identificador do cliente", example = "CUST-10001", maxLength = 64)
        @NotBlank(message = "é obrigatório")
        @Size(max = 64, message = "deve ter no máximo 64 caracteres")
        @Pattern(regexp = IDENTIFIER, message = IDENTIFIER_MESSAGE)
        String customerId,

        @Schema(description = "Código do produto a ativar", example = "INTERNET-500", maxLength = 64)
        @NotBlank(message = "é obrigatório")
        @Size(max = 64, message = "deve ter no máximo 64 caracteres")
        @Pattern(regexp = IDENTIFIER, message = IDENTIFIER_MESSAGE)
        String productCode) {

    static final String IDENTIFIER = "^[A-Za-z0-9][A-Za-z0-9._:-]*$";
    static final String IDENTIFIER_MESSAGE =
            "deve começar com letra ou dígito e conter apenas letras, dígitos, '.', '_', ':' ou '-'";
}
