package com.codexapi.server.session;

public record Session(
        String sessionId,
        String name,
        String workingDirectory,
        String description,
        String createdAt,
        String lastUsedAt,
        int defaultTimeoutSeconds
) {
}
