package com.codexapi.server.agent;

public record AgentConnectorStatus(
        boolean enabled,
        boolean active,
        boolean statePresent,
        String status,
        String message,
        String agentId,
        String agentType,
        String displayName,
        String bridgeBaseUrl,
        String relayWebSocketUrl,
        String connectUrl,
        String pairingCodeExpiresAt,
        boolean connected,
        String connectionStatus,
        String lastSeenAt,
        String lastConnectedAt,
        String lastDisconnectedAt
) {
    public static AgentConnectorStatus disabled() {
        return new AgentConnectorStatus(
                false,
                false,
                false,
                "disabled",
                "Connector mode is disabled.",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                "disabled",
                null,
                null,
                null
        );
    }

    public static AgentConnectorStatus inactive(String message) {
        return inactive(false, message, null, null);
    }

    public static AgentConnectorStatus inactive(
            boolean statePresent,
            String message,
            String bridgeBaseUrl,
            String agentId
    ) {
        return new AgentConnectorStatus(
                true,
                false,
                statePresent,
                "inactive",
                message,
                agentId,
                null,
                null,
                bridgeBaseUrl,
                null,
                null,
                null,
                false,
                "inactive",
                null,
                null,
                null
        );
    }

    public static AgentConnectorStatus ready(AgentState state, String message) {
        return new AgentConnectorStatus(
                true,
                true,
                true,
                "ready",
                message,
                state.agentId(),
                state.agentType(),
                state.displayName(),
                state.bridgeBaseUrl(),
                state.relayWebSocketUrl(),
                null,
                null,
                false,
                "connecting",
                null,
                state.lastConnectedAt(),
                null
        );
    }

    public AgentConnectorStatus withPairingCode(AgentPairingCodeResponse pairingCode) {
        return new AgentConnectorStatus(
                enabled,
                active,
                statePresent,
                status,
                message,
                agentId,
                agentType,
                displayName,
                bridgeBaseUrl,
                relayWebSocketUrl,
                pairingCode.connectUrl(),
                pairingCode.expiresAt(),
                connected,
                connectionStatus,
                lastSeenAt,
                lastConnectedAt,
                lastDisconnectedAt
        );
    }

    public AgentConnectorStatus withConnected(String timestamp) {
        return new AgentConnectorStatus(
                enabled,
                active,
                statePresent,
                status,
                message,
                agentId,
                agentType,
                displayName,
                bridgeBaseUrl,
                relayWebSocketUrl,
                connectUrl,
                pairingCodeExpiresAt,
                true,
                "connected",
                timestamp,
                timestamp,
                lastDisconnectedAt
        );
    }

    public AgentConnectorStatus withDisconnected(String reason, String timestamp) {
        return new AgentConnectorStatus(
                enabled,
                active,
                statePresent,
                status,
                reason == null || reason.isBlank() ? message : reason,
                agentId,
                agentType,
                displayName,
                bridgeBaseUrl,
                relayWebSocketUrl,
                connectUrl,
                pairingCodeExpiresAt,
                false,
                "disconnected",
                lastSeenAt,
                lastConnectedAt,
                timestamp
        );
    }

    public AgentConnectorStatus withSeen(String timestamp) {
        return new AgentConnectorStatus(
                enabled,
                active,
                statePresent,
                status,
                message,
                agentId,
                agentType,
                displayName,
                bridgeBaseUrl,
                relayWebSocketUrl,
                connectUrl,
                pairingCodeExpiresAt,
                connected,
                connectionStatus,
                timestamp,
                lastConnectedAt,
                lastDisconnectedAt
        );
    }
}
