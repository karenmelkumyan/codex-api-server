package com.codexapi.server.http;

import java.util.Map;

public final class ApiException extends RuntimeException {
    private final int statusCode;
    private final String code;
    private final Map<String, Object> details;

    public ApiException(int statusCode, String code, String message) {
        this(statusCode, code, message, Map.of());
    }

    public ApiException(int statusCode, String code, String message, Map<String, Object> details) {
        super(message);
        this.statusCode = statusCode;
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public int statusCode() {
        return statusCode;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }
}
