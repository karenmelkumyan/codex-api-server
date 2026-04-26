package com.codexapi.server.session;

public record CreateSessionRequest(
        String name,
        String workingDirectory,
        String description,
        Integer defaultTimeoutSeconds
) {
}
