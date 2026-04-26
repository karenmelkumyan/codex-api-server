package com.codexapi.server.http;

import java.util.Map;

public record ErrorResponse(ApiError error) {
    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(ApiError.of(code, message));
    }

    public static ErrorResponse of(String code, String message, Map<String, Object> details) {
        return new ErrorResponse(ApiError.of(code, message, details));
    }
}
