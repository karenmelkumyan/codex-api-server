package com.codexapi.server.agent;

import com.fasterxml.jackson.databind.JsonNode;

public record AgentStatusResponse(
        boolean ok,
        JsonNode agent,
        boolean relayEnabled,
        boolean bootstrapEnabled,
        String connectUrl,
        String relayWebSocketUrl
) {
}
