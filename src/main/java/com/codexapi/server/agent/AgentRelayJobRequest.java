package com.codexapi.server.agent;

public record AgentRelayJobRequest(
        String jobId,
        String tool,
        String message,
        Integer timeoutSeconds
) {
}
