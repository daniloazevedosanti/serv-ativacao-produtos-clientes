package com.desafio.activation.web;

import java.net.URI;

public final class ProblemTypes {

    private static final String BASE = "https://api.exemplo.com/problems/";

    public static final URI VALIDATION = URI.create(BASE + "validation-error");
    public static final URI MALFORMED_REQUEST = URI.create(BASE + "malformed-request");
    public static final URI NOT_FOUND = URI.create(BASE + "not-found");
    public static final URI DUPLICATE_EXTERNAL_ID = URI.create(BASE + "duplicate-external-id");
    public static final URI CONFLICT = URI.create(BASE + "conflict");
    public static final URI UNAUTHORIZED = URI.create(BASE + "unauthorized");
    public static final URI FORBIDDEN = URI.create(BASE + "forbidden");
    public static final URI PAYLOAD_TOO_LARGE = URI.create(BASE + "payload-too-large");
    public static final URI RATE_LIMITED = URI.create(BASE + "rate-limited");
    public static final URI INTERNAL = URI.create(BASE + "internal-error");

    private ProblemTypes() {
    }
}
