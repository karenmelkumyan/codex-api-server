package com.codexapi.server.agent;

public record AgentState(
        String agentId,
        String agentSecret,
        String agentType,
        String bridgeBaseUrl,
        String relayWebSocketUrl,
        String displayName,
        String createdAt,
        String lastConnectedAt
) {
    public AgentState {
        requireNonBlank(agentId, "agentId");
        requireNonBlank(agentSecret, "agentSecret");
        requireNonBlank(agentType, "agentType");
        requireNonBlank(bridgeBaseUrl, "bridgeBaseUrl");
        requireNonBlank(relayWebSocketUrl, "relayWebSocketUrl");
        requireNonBlank(displayName, "displayName");
        requireNonBlank(createdAt, "createdAt");
        if (lastConnectedAt != null && lastConnectedAt.isBlank()) {
            throw new IllegalArgumentException("lastConnectedAt must not be blank");
        }
    }

    public AgentState withLastConnectedAt(String timestamp) {
        requireNonBlank(timestamp, "lastConnectedAt");
        return new AgentState(
                agentId,
                agentSecret,
                agentType,
                bridgeBaseUrl,
                relayWebSocketUrl,
                displayName,
                createdAt,
                timestamp
        );
    }

    @Override
    public String toString() {
        return "AgentState{" +
                "agentId='" + agentId + '\'' +
                ", agentSecretPresent=" + !agentSecret.isBlank() +
                ", agentType='" + agentType + '\'' +
                ", bridgeBaseUrl='" + bridgeBaseUrl + '\'' +
                ", relayWebSocketUrl='" + relayWebSocketUrl + '\'' +
                ", displayName='" + displayName + '\'' +
                ", createdAt='" + createdAt + '\'' +
                ", lastConnectedAt='" + lastConnectedAt + '\'' +
                '}';
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
