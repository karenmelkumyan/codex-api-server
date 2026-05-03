package com.codexapi.server.agent;

public record AgentJobExecutionResult(
        String jobId,
        boolean ok,
        String status,
        String message,
        Integer exitCode,
        boolean timedOut,
        boolean stderrPresent,
        String error
) {
    public static AgentJobExecutionResult completed(String jobId, String message, Integer exitCode, boolean stderrPresent) {
        return new AgentJobExecutionResult(jobId, true, "completed", message, exitCode, false, stderrPresent, null);
    }

    public static AgentJobExecutionResult failed(
            String jobId,
            String message,
            Integer exitCode,
            boolean stderrPresent,
            String error
    ) {
        return new AgentJobExecutionResult(jobId, false, "failed", message, exitCode, false, stderrPresent, error);
    }

    public static AgentJobExecutionResult timedOut(String jobId, String message, boolean stderrPresent) {
        return new AgentJobExecutionResult(
                jobId,
                false,
                "timed_out",
                message,
                null,
                true,
                stderrPresent,
                "CODEX_EXEC_TIMED_OUT"
        );
    }
}
