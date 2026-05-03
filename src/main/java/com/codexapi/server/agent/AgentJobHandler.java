package com.codexapi.server.agent;

import com.codexapi.server.codex.CodexExecRequest;
import com.codexapi.server.codex.CodexExecResponse;
import com.codexapi.server.codex.CodexService;
import com.codexapi.server.config.Config;
import com.codexapi.server.http.ApiException;
import com.codexapi.server.session.Session;
import com.codexapi.server.session.SessionService;
import com.codexapi.server.session.SessionValidationException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class AgentJobHandler {
    public static final String JOB_ALREADY_RUNNING = "JOB_ALREADY_RUNNING";
    public static final String CODEX_EXEC_FAILED = "CODEX_EXEC_FAILED";
    public static final String LOCAL_CONNECTOR_NOT_CONFIGURED = "LOCAL_CONNECTOR_NOT_CONFIGURED";

    private static final String RELAY_SESSION_ID = "agent_connector";
    private static final String RELAY_SESSION_NAME = "Codex Agent Connector";

    private final Config config;
    private final SessionService sessionService;
    private final CodexService codexService;
    private final AgentToolPlanner planner;
    private final Executor executor;
    private final AtomicBoolean activeJob = new AtomicBoolean(false);
    private final boolean configured;

    public AgentJobHandler(Config config, SessionService sessionService, CodexService codexService) {
        this(config, sessionService, codexService, daemonExecutor());
    }

    AgentJobHandler(Config config, SessionService sessionService, CodexService codexService, Executor executor) {
        this.config = Objects.requireNonNull(config, "config");
        this.sessionService = Objects.requireNonNull(sessionService, "sessionService");
        this.codexService = Objects.requireNonNull(codexService, "codexService");
        this.planner = new AgentToolPlanner(config);
        this.executor = Objects.requireNonNull(executor, "executor");
        this.configured = true;
    }

    AgentJobHandler() {
        this.config = null;
        this.sessionService = null;
        this.codexService = null;
        this.planner = null;
        this.executor = Runnable::run;
        this.configured = false;
    }

    static AgentJobHandler notConfigured() {
        return new AgentJobHandler();
    }

    public CompletableFuture<AgentJobExecutionResult> handleAsync(AgentRelayJobRequest request) {
        if (!configured) {
            return CompletableFuture.completedFuture(localConnectorNotConfigured(jobId(request)));
        }
        if (!activeJob.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(AgentJobExecutionResult.failed(
                    jobId(request),
                    "A relay job is already running.",
                    null,
                    false,
                    JOB_ALREADY_RUNNING
            ));
        }

        return CompletableFuture.supplyAsync(() -> execute(request), executor)
                .whenComplete((result, exception) -> activeJob.set(false));
    }

    private AgentJobExecutionResult execute(AgentRelayJobRequest request) {
        String jobId = jobId(request);
        try {
            AgentToolExecutionPlan plan = planner.plan(request);
            Session session = connectorSession();
            CodexExecResponse response = codexService.execute(session, new CodexExecRequest(
                    plan.prompt(),
                    plan.timeoutSeconds(),
                    null,
                    null,
                    plan.sandbox(),
                    plan.approvalPolicy(),
                    plan.ephemeral(),
                    false
            ));
            boolean stderrPresent = stderrPresent(response);
            String message = nonBlankOrDefault(response.stdout(), response.exitCode() == null || response.exitCode() == 0
                    ? "Codex execution completed."
                    : "Codex execution failed.");

            if (response.exitCode() != null && response.exitCode() == 0) {
                return AgentJobExecutionResult.completed(jobId, message, response.exitCode(), stderrPresent);
            }

            return AgentJobExecutionResult.failed(
                    jobId,
                    message,
                    response.exitCode(),
                    stderrPresent,
                    CODEX_EXEC_FAILED
            );
        } catch (AgentToolPlanningException exception) {
            return AgentJobExecutionResult.failed(jobId, exception.getMessage(), null, false, exception.errorCode());
        } catch (SessionValidationException | IllegalArgumentException exception) {
            return localConnectorNotConfigured(jobId);
        } catch (ApiException exception) {
            return mapApiException(jobId, exception);
        } catch (RuntimeException exception) {
            return AgentJobExecutionResult.failed(
                    jobId,
                    "Codex execution failed.",
                    null,
                    false,
                    CODEX_EXEC_FAILED
            );
        }
    }

    private Session connectorSession() {
        Path workingDirectory = Path.of(config.agent().workingDirectory()).toAbsolutePath().normalize();
        if (!Files.isDirectory(workingDirectory)) {
            throw new SessionValidationException("workingDirectory must be a directory", "workingDirectory");
        }

        Session existingSession = sessionService.store().find(RELAY_SESSION_ID).orElse(null);
        if (existingSession != null && workingDirectory.toString().equals(existingSession.workingDirectory())) {
            return existingSession;
        }

        String now = Instant.now().toString();
        Session session = new Session(
                RELAY_SESSION_ID,
                RELAY_SESSION_NAME,
                workingDirectory.toString(),
                "Internal session for outbound connector relay jobs",
                existingSession == null ? now : existingSession.createdAt(),
                existingSession == null ? null : existingSession.lastUsedAt(),
                Math.min(config.agent().jobMaxTimeoutSeconds(), config.execMaxTimeoutSeconds())
        );
        return sessionService.store().save(session);
    }

    private AgentJobExecutionResult mapApiException(String jobId, ApiException exception) {
        if ("PROCESS_TIMEOUT".equals(exception.code())) {
            return AgentJobExecutionResult.timedOut(
                    jobId,
                    "Codex execution timed out.",
                    detailTextPresent(exception, "stderr")
            );
        }
        if ("VALIDATION_ERROR".equals(exception.code())) {
            return localConnectorNotConfigured(jobId);
        }
        return AgentJobExecutionResult.failed(
                jobId,
                "Codex execution failed.",
                null,
                false,
                CODEX_EXEC_FAILED
        );
    }

    private AgentJobExecutionResult localConnectorNotConfigured(String jobId) {
        return AgentJobExecutionResult.failed(
                jobId,
                "Local connector is not configured for relay job execution.",
                null,
                false,
                LOCAL_CONNECTOR_NOT_CONFIGURED
        );
    }

    private boolean stderrPresent(CodexExecResponse response) {
        return response.stderrTruncated() || (response.stderr() != null && !response.stderr().isBlank());
    }

    private boolean detailTextPresent(ApiException exception, String key) {
        Object value = exception.details().get(key);
        return value instanceof String text && !text.isBlank();
    }

    private String nonBlankOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private String jobId(AgentRelayJobRequest request) {
        return request == null || request.jobId() == null || request.jobId().isBlank() ? "" : request.jobId().trim();
    }

    private static Executor daemonExecutor() {
        return Executors.newSingleThreadExecutor(command -> {
            Thread thread = new Thread(command, "codex-agent-job");
            thread.setDaemon(true);
            return thread;
        });
    }
}
