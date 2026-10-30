package com.desafio.activation.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Formato restrito de propósito: os três campos viram chave de busca, header e log.
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
