package com.codexapi.server.agent;

public record AgentToolExecutionPlan(
        String prompt,
        String sandbox,
        String approvalPolicy,
        boolean ephemeral,
        int timeoutSeconds
) {
}
