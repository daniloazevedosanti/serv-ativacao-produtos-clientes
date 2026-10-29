package com.desafio.activation.application;

/**
 * O externalId já pertence a outra solicitação com conteúdo diferente (ou de outro consumidor).
 * A mensagem é propositalmente genérica: não revela dados da solicitação existente.
 */
public class DuplicateExternalIdException extends RuntimeException {

    private final String externalId;

    public DuplicateExternalIdException(String externalId) {
        super("O externalId '" + externalId + "' já foi utilizado com dados diferentes");
        this.externalId = externalId;
    }

    public String externalId() {
        return externalId;
    }
}
