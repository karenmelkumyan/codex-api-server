package com.codexapi.server.agent;

public record AgentPairingCodeResponse(
        boolean ok,
        String agentId,
        String pairingCode,
        String expiresAt,
        String connectUrl
) {
    @Override
    public String toString() {
        return "AgentPairingCodeResponse{" +
                "ok=" + ok +
                ", agentId='" + agentId + '\'' +
                ", pairingCodePresent=" + (pairingCode != null && !pairingCode.isBlank()) +
                ", expiresAt='" + expiresAt + '\'' +
                ", connectUrl='" + connectUrl + '\'' +
                '}';
    }
}
