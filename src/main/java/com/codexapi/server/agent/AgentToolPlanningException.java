package com.codexapi.server.agent;

public final class AgentToolPlanningException extends RuntimeException {
    private final String errorCode;

    public AgentToolPlanningException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
