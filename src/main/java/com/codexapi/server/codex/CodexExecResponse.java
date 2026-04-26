package com.codexapi.server.codex;

public record CodexExecResponse(
        String executionId,
        String sessionId,
        String startedAt,
        String finishedAt,
        Integer exitCode,
        long durationMs,
        boolean timedOut,
        String stdout,
        String stderr,
        boolean stdoutTruncated,
        boolean stderrTruncated,
        CodexCommand command
) {
}
