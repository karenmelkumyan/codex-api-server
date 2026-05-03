package com.codexapi.server.agent;

public final class BridgeAgentClientException extends RuntimeException {
    public BridgeAgentClientException(String message) {
        super(message);
    }

    public BridgeAgentClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
