package com.codexapi.server.process;

import java.util.List;

public record ProcessResult(
        List<String> command,
        boolean started,
        boolean timedOut,
        String stdout,
        String stderr,
        boolean stdoutTruncated,
        boolean stderrTruncated,
        Integer exitCode,
        long durationMs,
        String errorMessage
) {
    public ProcessResult {
        command = List.copyOf(command);
        stdout = stdout == null ? "" : stdout;
        stderr = stderr == null ? "" : stderr;
    }

    public static ProcessResult completed(
            List<String> command,
            String stdout,
            String stderr,
            boolean stdoutTruncated,
            boolean stderrTruncated,
            int exitCode,
            long durationMs
    ) {
        return new ProcessResult(
                command,
                true,
                false,
                stdout,
                stderr,
                stdoutTruncated,
                stderrTruncated,
                exitCode,
                durationMs,
                null
        );
    }

    public static ProcessResult failedToStart(List<String> command, long durationMs, String errorMessage) {
        return new ProcessResult(command, false, false, "", "", false, false, null, durationMs, errorMessage);
    }

    public static ProcessResult timedOut(
            List<String> command,
            String stdout,
            String stderr,
            boolean stdoutTruncated,
            boolean stderrTruncated,
            Integer exitCode,
            long durationMs
    ) {
        return new ProcessResult(
                command,
                true,
                true,
                stdout,
                stderr,
                stdoutTruncated,
                stderrTruncated,
                exitCode,
                durationMs,
                "Process timed out"
        );
    }

    public static ProcessResult interrupted(
            List<String> command,
            String stdout,
            String stderr,
            boolean stdoutTruncated,
            boolean stderrTruncated,
            long durationMs
    ) {
        return new ProcessResult(
                command,
                true,
                false,
                stdout,
                stderr,
                stdoutTruncated,
                stderrTruncated,
                null,
                durationMs,
                "Process interrupted"
        );
    }
}
