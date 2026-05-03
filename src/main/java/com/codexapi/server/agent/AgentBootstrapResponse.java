package com.codexapi.server.agent;

public record AgentBootstrapResponse(
        boolean ok,
        String agentId,
        String agentSecret,
        String agentType,
        String displayName,
        String clientVersion,
        String status,
        String createdAt,
        boolean relayEnabled,
        String connectUrl,
        String relayWebSocketUrl
) {
    public AgentBootstrapResponse withRelayWebSocketUrl(String value) {
        return new AgentBootstrapResponse(
                ok,
                agentId,
                agentSecret,
                agentType,
                displayName,
                clientVersion,
                status,
                createdAt,
                relayEnabled,
                connectUrl,
                value
        );
    }

    @Override
    public String toString() {
        return "AgentBootstrapResponse{" +
                "ok=" + ok +
                ", agentId='" + agentId + '\'' +
                ", agentSecretPresent=" + (agentSecret != null && !agentSecret.isBlank()) +
                ", agentType='" + agentType + '\'' +
                ", displayName='" + displayName + '\'' +
                ", clientVersion='" + clientVersion + '\'' +
                ", status='" + status + '\'' +
                ", createdAt='" + createdAt + '\'' +
                ", relayEnabled=" + relayEnabled +
                ", connectUrl='" + connectUrl + '\'' +
                ", relayWebSocketUrl='" + relayWebSocketUrl + '\'' +
                '}';
    }
}
