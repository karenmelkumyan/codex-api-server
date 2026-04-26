package com.codexapi.server.git;

import com.codexapi.server.http.ApiException;
import com.codexapi.server.process.ProcessResult;
import com.codexapi.server.process.ProcessRunner;
import com.codexapi.server.session.Session;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GitService {
    private final ProcessRunner processRunner;
    private final Duration timeout;

    public GitService(ProcessRunner processRunner, Duration timeout) {
        this.processRunner = processRunner;
        this.timeout = timeout;
    }

    public Map<String, Object> status(Session session) {
        ProcessResult result = processRunner.run(
                List.of("git", "--no-pager", "status", "--short", "--branch"),
                timeout,
                Path.of(session.workingDirectory())
        );
        ensureProcessStarted(result);

        Map<String, Object> response = baseResponse(session, result);
        response.put("stdout", result.stdout());
        response.put("stderr", result.stderr());
        return response;
    }

    public Map<String, Object> diff(Session session, boolean staged, int maxBytes) {
        if (maxBytes < 1) {
            throw new ApiException(400, "VALIDATION_ERROR", "maxBytes must be positive", Map.of("field", "maxBytes"));
        }

        List<String> command = staged
                ? List.of("git", "--no-pager", "diff", "--no-ext-diff", "--staged")
                : List.of("git", "--no-pager", "diff", "--no-ext-diff");
        ProcessResult result = processRunner.run(
                command,
                timeout,
                Path.of(session.workingDirectory()),
                maxBytes,
                ProcessRunner.DEFAULT_STDERR_BYTES
        );
        ensureProcessStarted(result);

        Map<String, Object> response = baseResponse(session, result);
        response.put("staged", staged);
        response.put("stdout", result.stdout());
        response.put("stderr", result.stderr());
        response.put("truncated", result.stdoutTruncated());
        return response;
    }

    private static Map<String, Object> baseResponse(Session session, ProcessResult result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sessionId", session.sessionId());
        response.put("exitCode", result.exitCode());
        response.put("durationMs", result.durationMs());
        return response;
    }

    private static void ensureProcessStarted(ProcessResult result) {
        if (!result.started() || result.timedOut() || result.exitCode() == null) {
            throw new ApiException(
                    500,
                    "PROCESS_FAILED",
                    "Unable to run git command.",
                    Map.of("error", result.errorMessage() == null ? "Process did not complete" : result.errorMessage())
            );
        }
    }

}
