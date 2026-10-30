package com.desafio.activation.web;

import java.io.IOException;

public class PayloadTooLargeException extends IOException {

    public PayloadTooLargeException(long limit) {
        super("Corpo da requisição excede o limite de " + limit + " bytes");
    }
}
