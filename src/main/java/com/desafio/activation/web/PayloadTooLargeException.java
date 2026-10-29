package com.desafio.activation.web;

import java.io.IOException;

/** Corpo da requisição acima do limite (inclusive quando enviado em chunks, sem Content-Length). */
public class PayloadTooLargeException extends IOException {

    public PayloadTooLargeException(long limit) {
        super("Corpo da requisição excede o limite de " + limit + " bytes");
    }
}
