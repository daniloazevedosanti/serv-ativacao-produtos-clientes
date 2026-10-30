package com.desafio.activation.application;

/**
 * Mensagem genérica de propósito: o externalId pode pertencer a outro consumidor.
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
