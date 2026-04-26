package com.codexapi.server.history;

public record ExecutionRecord(
        String executionId,
        String sessionId,
        String startedAt,
        String finishedAt,
        Integer exitCode,
        long durationMs,
        boolean timedOut,
        String promptPreview,
        String stdoutPreview,
        String stderrPreview
) {
}
