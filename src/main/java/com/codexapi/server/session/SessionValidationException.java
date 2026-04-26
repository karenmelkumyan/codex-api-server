package com.codexapi.server.session;

import java.util.Map;

public final class SessionValidationException extends RuntimeException {
    private final Map<String, Object> details;

    public SessionValidationException(String message, String field) {
        super(message);
        this.details = Map.of("field", field);
    }

    public Map<String, Object> details() {
        return details;
    }
}
