package com.codexapi.server.codex;

import com.codexapi.server.config.Config;
import com.codexapi.server.history.ExecutionRecord;
import com.codexapi.server.history.HistoryService;
import com.codexapi.server.http.ApiException;
import com.codexapi.server.process.ProcessResult;
import com.codexapi.server.process.ProcessRunner;
import com.codexapi.server.session.Session;
import com.codexapi.server.session.SessionService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class CodexService {
    private static final Set<String> ALLOWED_SANDBOXES = Set.of("read-only", "workspace-write");
    private static final Set<String> ALLOWED_APPROVAL_POLICIES = Set.of("untrusted", "on-request", "never");
    private static final int OUTPUT_PREVIEW_LENGTH = 500;
    private static final String TRUNCATION_MARKER = "...[truncated]";
    private static final String PROMPT_OMITTED = "[prompt omitted]";

    private final Config config;
    private final ProcessRunner processRunner;
    private final SessionService sessionService;
    private final HistoryService historyService;

    public CodexService(
            Config config,
            ProcessRunner processRunner,
            SessionService sessionService,
            HistoryService historyService
    ) {
        this.config = config;
        this.processRunner = processRunner;
        this.sessionService = sessionService;
        this.historyService = historyService;
    }

    public CodexExecResponse execute(Session session, CodexExecRequest request) {
        ValidatedRequest validatedRequest = validate(request, session.defaultTimeoutSeconds());
        String executionId = generateExecutionId();
        String startedAt = Instant.now().toString();
        Path outputFile = createOutputFile(executionId);
        CodexCommand command = buildCommand(session, validatedRequest, outputFile);

        List<String> processCommand = new ArrayList<>();
        processCommand.add(command.executable());
        processCommand.addAll(command.args());

        ProcessResult result = processRunner.run(
                processCommand,
                Duration.ofSeconds(validatedRequest.timeoutSeconds()),
                Path.of(session.workingDirectory()),
                ProcessRunner.DEFAULT_STDOUT_BYTES,
                ProcessRunner.DEFAULT_STDERR_BYTES,
                validatedRequest.prompt()
        );

        String finishedAt = Instant.now().toString();
        OutputText stdout = stdout(result, outputFile, validatedRequest.prompt());
        String stderr = sanitizePrompt(result.stderr(), validatedRequest.prompt());
        deleteOutputFile(outputFile);

        CodexExecResponse response = new CodexExecResponse(
                executionId,
                session.sessionId(),
                startedAt,
                finishedAt,
                result.exitCode(),
                result.durationMs(),
                result.timedOut(),
                stdout.value(),
                stderr,
                stdout.truncated(),
                result.stderrTruncated(),
                command
        );

        persistExecutionMetadata(response);

        if (!result.started()) {
            throw new ApiException(
                    502,
                    "CODEX_NOT_AVAILABLE",
                    "Codex CLI could not be started.",
                    Map.of("executionId", executionId, "cliPath", config.codexCliPath())
            );
        }

        if (result.timedOut()) {
            throw new ApiException(
                    408,
                    "PROCESS_TIMEOUT",
                    "Codex execution timed out.",
                    timeoutDetails(response)
            );
        }

        return response;
    }

    private ValidatedRequest validate(CodexExecRequest request, int sessionDefaultTimeoutSeconds) {
        if (request == null) {
            throw validationError("Request body is required", "body");
        }

        String prompt = requiredString(request.prompt(), "prompt");
        int timeoutSeconds = timeoutSeconds(request.timeoutSeconds(), sessionDefaultTimeoutSeconds);
        String model = optionalString(request.model(), "model");
        String profile = optionalString(request.profile(), "profile");
        String sandbox = optionalChoice(request.sandbox(), "sandbox", ALLOWED_SANDBOXES);
        String approvalPolicy = optionalChoice(request.approvalPolicy(), "approvalPolicy", ALLOWED_APPROVAL_POLICIES);
        boolean ephemeral = Boolean.TRUE.equals(request.ephemeral());
        boolean skipGitRepoCheck = Boolean.TRUE.equals(request.skipGitRepoCheck());

        return new ValidatedRequest(
                prompt,
                timeoutSeconds,
                model,
                profile,
                sandbox,
                approvalPolicy,
                ephemeral,
                skipGitRepoCheck
        );
    }

    private CodexCommand buildCommand(Session session, ValidatedRequest request, Path outputFile) {
        List<String> args = new ArrayList<>();
        args.add("exec");
        args.add("--cd");
        args.add(session.workingDirectory());
        args.add("--color");
        args.add("never");

        if (request.model() != null) {
            args.add("--model");
            args.add(request.model());
        }
        if (request.profile() != null) {
            args.add("--profile");
            args.add(request.profile());
        }
        if (request.sandbox() != null) {
            args.add("--sandbox");
            args.add(request.sandbox());
        }
        if (request.approvalPolicy() != null) {
            args.add("-c");
            args.add("approval_policy=\"" + request.approvalPolicy() + "\"");
        }
        if (request.ephemeral()) {
            args.add("--ephemeral");
        }
        if (request.skipGitRepoCheck()) {
            args.add("--skip-git-repo-check");
        }

        args.add("--output-last-message");
        args.add(outputFile.toString());
        args.add("-");
        return new CodexCommand(config.codexCliPath(), args);
    }

    private void persistExecutionMetadata(CodexExecResponse response) {
        historyService.add(new ExecutionRecord(
                response.executionId(),
                response.sessionId(),
                response.startedAt(),
                response.finishedAt(),
                response.exitCode(),
                response.durationMs(),
                response.timedOut(),
                PROMPT_OMITTED,
                preview(response.stdout(), OUTPUT_PREVIEW_LENGTH),
                preview(response.stderr(), OUTPUT_PREVIEW_LENGTH)
        ));
        sessionService.touchLastUsedAt(response.sessionId(), response.finishedAt());
    }

    CodexCommand buildCommand(Session session, CodexExecRequest request, Path outputFile) {
        return buildCommand(session, validate(request, session.defaultTimeoutSeconds()), outputFile);
    }

    private Map<String, Object> timeoutDetails(CodexExecResponse response) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("executionId", response.executionId());
        details.put("sessionId", response.sessionId());
        details.put("startedAt", response.startedAt());
        details.put("finishedAt", response.finishedAt());
        details.put("durationMs", response.durationMs());
        details.put("timedOut", response.timedOut());
        details.put("stdout", response.stdout());
        details.put("stderr", response.stderr());
        details.put("stdoutTruncated", response.stdoutTruncated());
        details.put("stderrTruncated", response.stderrTruncated());
        details.put("command", response.command());
        return details;
    }

    private Path createOutputFile(String executionId) {
        try {
            return Files.createTempFile("codex-api-" + executionId + "-", ".txt");
        } catch (IOException exception) {
            throw new ApiException(500, "INTERNAL_ERROR", "Unable to prepare Codex output capture.");
        }
    }

    private OutputText stdout(ProcessResult result, Path outputFile, String prompt) {
        OutputText outputText = readOutputFile(outputFile);
        if (!outputText.value().isBlank() || outputText.truncated()) {
            return new OutputText(sanitizePrompt(outputText.value(), prompt), outputText.truncated());
        }
        return new OutputText(sanitizePrompt(result.stdout(), prompt), result.stdoutTruncated());
    }

    private OutputText readOutputFile(Path outputFile) {
        if (!Files.exists(outputFile)) {
            return new OutputText("", false);
        }

        try (InputStream inputStream = Files.newInputStream(outputFile)) {
            byte[] bytes = inputStream.readNBytes(ProcessRunner.DEFAULT_STDOUT_BYTES + 1);
            boolean truncated = bytes.length > ProcessRunner.DEFAULT_STDOUT_BYTES;
            if (truncated) {
                byte[] truncatedBytes = new byte[ProcessRunner.DEFAULT_STDOUT_BYTES];
                System.arraycopy(bytes, 0, truncatedBytes, 0, ProcessRunner.DEFAULT_STDOUT_BYTES);
                bytes = truncatedBytes;
            }
            return new OutputText(new String(bytes, StandardCharsets.UTF_8), truncated);
        } catch (IOException exception) {
            return new OutputText("", false);
        }
    }

    private void deleteOutputFile(Path outputFile) {
        try {
            Files.deleteIfExists(outputFile);
        } catch (IOException exception) {
            // Temporary output cleanup is best effort.
        }
    }

    private String requiredString(String value, String field) {
        if (value == null || value.isBlank()) {
            throw validationError(field + " is required", field);
        }
        return value.trim();
    }

    private String optionalString(String value, String field) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            throw validationError(field + " must be non-blank when provided", field);
        }
        return value.trim();
    }

    private String optionalChoice(String value, String field, Set<String> allowedValues) {
        if (value == null) {
            return null;
        }
        String normalizedValue = value.trim();
        if (normalizedValue.isBlank() || !allowedValues.contains(normalizedValue)) {
            throw validationError(field + " is not supported", field);
        }
        return normalizedValue;
    }

    private int timeoutSeconds(Integer requestedTimeoutSeconds, int sessionDefaultTimeoutSeconds) {
        int timeoutSeconds = requestedTimeoutSeconds == null ? sessionDefaultTimeoutSeconds : requestedTimeoutSeconds;
        if (timeoutSeconds <= 0) {
            throw validationError("timeoutSeconds must be positive", "timeoutSeconds");
        }
        if (timeoutSeconds > config.execMaxTimeoutSeconds()) {
            throw validationError("timeoutSeconds must not exceed CODEX_EXEC_MAX_TIMEOUT", "timeoutSeconds");
        }
        return timeoutSeconds;
    }

    private ApiException validationError(String message, String field) {
        return new ApiException(400, "VALIDATION_ERROR", message, Map.of("field", field));
    }

    private String preview(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalizedValue = value.strip();
        if (normalizedValue.length() <= maxLength) {
            return normalizedValue;
        }
        int prefixLength = Math.max(0, maxLength - TRUNCATION_MARKER.length());
        return normalizedValue.substring(0, prefixLength) + TRUNCATION_MARKER;
    }

    private String sanitizePrompt(String value, String prompt) {
        if (value == null || value.isEmpty() || prompt == null || prompt.isEmpty()) {
            return value == null ? "" : value;
        }
        return value.replace(prompt, "[prompt omitted]");
    }

    private String generateExecutionId() {
        return "e_" + UUID.randomUUID().toString().replace("-", "");
    }

    private record ValidatedRequest(
            String prompt,
            int timeoutSeconds,
            String model,
            String profile,
            String sandbox,
            String approvalPolicy,
            boolean ephemeral,
            boolean skipGitRepoCheck
    ) {
    }

    private record OutputText(String value, boolean truncated) {
    }
}
