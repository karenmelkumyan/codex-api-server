package com.codexapi.server.http;

import com.codexapi.server.util.JsonUtil;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class JsonResponseWriter {
    private JsonResponseWriter() {
    }

    public static void writeJson(HttpExchange exchange, int statusCode, Object body) throws IOException {
        byte[] response = JsonUtil.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, response.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(response);
        }
    }

    public static void writeError(HttpExchange exchange, int statusCode, String code, String message)
            throws IOException {
        writeJson(exchange, statusCode, ErrorResponse.of(code, message));
    }

    public static void writeError(
            HttpExchange exchange,
            int statusCode,
            String code,
            String message,
            Map<String, Object> details
    ) throws IOException {
        writeJson(exchange, statusCode, ErrorResponse.of(code, message, details));
    }
}
