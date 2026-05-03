package com.codexapi.server.agent;

public final class AgentConnectorException extends RuntimeException {
    private final int statusCode;
    private final String code;

    public AgentConnectorException(int statusCode, String code, String message) {
        super(message);
        this.statusCode = statusCode;
        this.code = code;
    }

    public AgentConnectorException(int statusCode, String code, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.code = code;
    }

    public int statusCode() {
        return statusCode;
    }

    public String code() {
        return code;
    }
}
