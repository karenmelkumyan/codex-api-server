package com.codexapi.server.agent;

import com.codexapi.server.config.Config;

public final class AgentToolPlanner {
    public static final String UNSUPPORTED_TOOL = "UNSUPPORTED_TOOL";
    public static final String INVALID_JOB_REQUEST = "INVALID_JOB_REQUEST";
    private static final String APPROVAL_POLICY = "never";
    private static final boolean EPHEMERAL = true;
    private static final String READONLY_SANDBOX = "read-only";
    private static final String WORKSPACE_WRITE_SANDBOX = "workspace-write";

    private static final String READONLY_PROMPT_PREFIX = """
            Tool mode: codex_readonly.

            Inspect the repository and answer the request without modifying files.
            Do not intentionally change source files, configuration, generated files, or documentation.

            Request:
            """;
    private static final String VERIFY_PROMPT_PREFIX = """
            Tool mode: codex_verify.

            Verification step only.
            Run relevant checks, tests, builds, or diagnostics if useful.
            Do not intentionally edit source files or documentation.
            Generated build/test artifacts are acceptable if they are a normal side effect of verification.

            Request:
            """;
    private static final String CHANGE_PROMPT_PREFIX = """
            Tool mode: codex_change.

            Make the smallest coherent repository change that satisfies the request.
            Preserve existing behavior unless the request requires changing it.
            Update README.md and SPEC.md when behavior, configuration, endpoints, or usage instructions change.
            After changes, report what changed and how it was verified.

            Request:
            """;

    private final Config config;

    public AgentToolPlanner(Config config) {
        this.config = config;
    }

    public AgentToolExecutionPlan plan(AgentRelayJobRequest request) {
        if (request == null) {
            throw unsupported("Job request is required.");
        }
        String tool = request.tool() == null ? "" : request.tool().trim();
        return switch (tool) {
            case "codex_readonly" -> plan(READONLY_PROMPT_PREFIX, READONLY_SANDBOX, request);
            case "codex_verify" -> plan(VERIFY_PROMPT_PREFIX, WORKSPACE_WRITE_SANDBOX, request);
            case "codex_change" -> plan(CHANGE_PROMPT_PREFIX, WORKSPACE_WRITE_SANDBOX, request);
            case "codex_read_log" -> throw unsupported("codex_read_log is not supported as a relay job.");
            default -> throw unsupported("Unsupported Codex tool: " + tool);
        };
    }

    private AgentToolExecutionPlan plan(
            String promptPrefix,
            String sandbox,
            AgentRelayJobRequest request
    ) {
        String message = requiredMessage(request.message());
        int timeoutSeconds = effectiveTimeoutSeconds(request.timeoutSeconds());
        return new AgentToolExecutionPlan(
                promptPrefix + message,
                sandbox,
                APPROVAL_POLICY,
                EPHEMERAL,
                timeoutSeconds
        );
    }

    private int effectiveTimeoutSeconds(Integer requestedTimeoutSeconds) {
        int cap = Math.min(config.agent().jobMaxTimeoutSeconds(), config.execMaxTimeoutSeconds());
        if (requestedTimeoutSeconds == null) {
            return cap;
        }
        if (requestedTimeoutSeconds < 1) {
            throw invalid("timeoutSeconds must be greater than zero.");
        }
        return Math.min(requestedTimeoutSeconds, cap);
    }

    private String requiredMessage(String value) {
        if (value == null || value.isBlank()) {
            throw invalid("message is required.");
        }
        return value;
    }

    private AgentToolPlanningException unsupported(String message) {
        return new AgentToolPlanningException(UNSUPPORTED_TOOL, message);
    }

    private AgentToolPlanningException invalid(String message) {
        return new AgentToolPlanningException(INVALID_JOB_REQUEST, message);
    }
}
