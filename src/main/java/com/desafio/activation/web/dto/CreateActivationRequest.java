package com.desafio.activation.web.dto;

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
        @NotBlank(message = "é obrigatório")
        @Size(max = 64, message = "deve ter no máximo 64 caracteres")
        @Pattern(regexp = IDENTIFIER, message = IDENTIFIER_MESSAGE)
        String externalId,

        @NotBlank(message = "é obrigatório")
        @Size(max = 64, message = "deve ter no máximo 64 caracteres")
        @Pattern(regexp = IDENTIFIER, message = IDENTIFIER_MESSAGE)
        String customerId,

        @NotBlank(message = "é obrigatório")
        @Size(max = 64, message = "deve ter no máximo 64 caracteres")
        @Pattern(regexp = IDENTIFIER, message = IDENTIFIER_MESSAGE)
        String productCode) {

    static final String IDENTIFIER = "^[A-Za-z0-9][A-Za-z0-9._:-]*$";
    static final String IDENTIFIER_MESSAGE =
            "deve começar com letra ou dígito e conter apenas letras, dígitos, '.', '_', ':' ou '-'";
}
