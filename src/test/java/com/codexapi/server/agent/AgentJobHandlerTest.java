package com.codexapi.server.agent;

import com.codexapi.server.codex.CodexService;
import com.codexapi.server.config.AgentConfig;
import com.codexapi.server.config.Config;
import com.codexapi.server.history.HistoryService;
import com.codexapi.server.process.ProcessRunner;
import com.codexapi.server.session.SessionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AgentJobHandlerTest {
    @TempDir
    Path tempDir;

    @Test
    void successfulCodexExecutionMapsToCompletedSafeResult() throws Exception {
        AgentJobExecutionResult result = handler(script("""
                #!/bin/sh
                cat >/dev/null
                echo "success output"
                echo "raw stderr secret" >&2
                exit 0
                """)).handleAsync(job("codex_readonly", 5)).join();

        assertEquals(true, result.ok());
        assertEquals("completed", result.status());
        assertEquals(0, result.exitCode());
        assertEquals("success output", result.message());
        assertEquals(true, result.stderrPresent());
        assertEquals(null, result.error());
        assertFalse(result.toString().contains("raw stderr secret"));
    }

    @Test
    void nonzeroCodexExecutionMapsToFailedSafeResult() throws Exception {
        AgentJobExecutionResult result = handler(script("""
                #!/bin/sh
                cat >/dev/null
                echo "failure output"
                echo "raw stderr secret" >&2
                exit 7
                """)).handleAsync(job("codex_change", 5)).join();

        assertEquals(false, result.ok());
        assertEquals("failed", result.status());
        assertEquals(7, result.exitCode());
        assertEquals("failure output", result.message());
        assertEquals(true, result.stderrPresent());
        assertEquals("CODEX_EXEC_FAILED", result.error());
        assertFalse(result.toString().contains("raw stderr secret"));
    }

    @Test
    void nonzeroCodexExecutionUsesGenericFailureWhenStdoutIsBlank() throws Exception {
        AgentJobExecutionResult result = handler(script("""
                #!/bin/sh
                cat >/dev/null
                echo "codex transcript tail" >&2
                exit 7
                """)).handleAsync(job("codex_change", 5)).join();

        assertEquals(false, result.ok());
        assertEquals("failed", result.status());
        assertEquals(7, result.exitCode());
        assertEquals("Codex execution failed.", result.message());
        assertFalse(result.message().contains("codex transcript tail"));
        assertEquals(true, result.stderrPresent());
        assertEquals("CODEX_EXEC_FAILED", result.error());
    }

    @Test
    void timeoutMapsToTimedOutSafeResult() throws Exception {
        AgentJobExecutionResult result = handler(script("""
                #!/bin/sh
                sleep 5
                """)).handleAsync(job("codex_verify", 1)).join();

        assertEquals(false, result.ok());
        assertEquals("timed_out", result.status());
        assertEquals(true, result.timedOut());
        assertEquals("CODEX_EXEC_TIMED_OUT", result.error());
        assertEquals(false, result.stderrPresent());
    }

    @Test
    void timeoutUsesGenericMessageWhenCapturedStderrIsPresent() throws Exception {
        AgentJobExecutionResult result = handler(script("""
                #!/bin/sh
                echo "still working on static site" >&2
                sleep 5
                """)).handleAsync(job("codex_verify", 1)).join();

        assertEquals(false, result.ok());
        assertEquals("timed_out", result.status());
        assertEquals("Codex execution timed out.", result.message());
        assertFalse(result.message().contains("still working on static site"));
        assertEquals("CODEX_EXEC_TIMED_OUT", result.error());
        assertEquals(true, result.stderrPresent());
    }

    @Test
    void unsupportedToolMapsToUnsupportedTool() throws Exception {
        AgentJobExecutionResult result = handler(script("""
                #!/bin/sh
                echo "should not run"
                """)).handleAsync(job("codex_read_log", 5)).join();

        assertEquals(false, result.ok());
        assertEquals("failed", result.status());
        assertEquals("UNSUPPORTED_TOOL", result.error());
    }

    @Test
    void invalidJobRequestMapsToValidationFailureWithoutRunningCodex() throws Exception {
        AgentJobExecutionResult result = handler(script("""
                #!/bin/sh
                echo "should not run"
                """)).handleAsync(new AgentRelayJobRequest("job_1", "codex_change", "", 0)).join();

        assertEquals(false, result.ok());
        assertEquals("failed", result.status());
        assertEquals("INVALID_JOB_REQUEST", result.error());
        assertEquals(false, result.message().contains("should not run"));
    }

    @Test
    void missingWorkingDirectoryMapsToLocalConnectorNotConfigured() throws Exception {
        Path codex = script("""
                #!/bin/sh
                echo "should not run"
                """);
        Config config = configUnchecked(codex, tempDir.resolve("missing"), 5, 5);
        SessionService sessionService = new SessionService(config);
        AgentJobHandler handler = new AgentJobHandler(
                config,
                sessionService,
                new CodexService(config, new ProcessRunner(), sessionService, new HistoryService(sessionService.store())),
                Runnable::run
        );

        AgentJobExecutionResult result = handler.handleAsync(job("codex_change", 5)).join();

        assertEquals(false, result.ok());
        assertEquals("LOCAL_CONNECTOR_NOT_CONFIGURED", result.error());
    }

    @Test
    void alreadyRunningMapsToJobAlreadyRunning() throws Exception {
        Executor neverRuns = command -> {
        };
        Path codex = script("""
                #!/bin/sh
                echo "pending"
                """);
        Config config = config(codex, tempDir, 5, 5);
        SessionService sessionService = new SessionService(config);
        AgentJobHandler handler = new AgentJobHandler(
                config,
                sessionService,
                new CodexService(config, new ProcessRunner(), sessionService, new HistoryService(sessionService.store())),
                neverRuns
        );

        handler.handleAsync(job("codex_change", 5));
        AgentJobExecutionResult result = handler.handleAsync(job("codex_verify", 5)).join();

        assertEquals(false, result.ok());
        assertEquals("JOB_ALREADY_RUNNING", result.error());
    }

    @Test
    void savesConnectorSessionInSharedSessionStore() throws Exception {
        SessionService sessionService = new SessionService(config(script("""
                #!/bin/sh
                echo "ok"
                """), tempDir, 5, 5));
        Config config = config(script("""
                #!/bin/sh
                echo "ok"
                """), tempDir, 5, 5);
        sessionService = new SessionService(config);
        AgentJobHandler handler = new AgentJobHandler(
                config,
                sessionService,
                new CodexService(config, new ProcessRunner(), sessionService, new HistoryService(sessionService.store())),
                Runnable::run
        );

        handler.handleAsync(job("codex_change", 5)).join();

        assertTrue(sessionService.store().find("agent_connector").isPresent());
    }

    @Test
    void relayJobsAllowNonGitWorkingDirectories() throws Exception {
        AgentJobExecutionResult result = handler(script("""
                #!/bin/sh
                for arg in "$@"; do
                  if [ "$arg" = "--skip-git-repo-check" ]; then
                    echo "skip git check enabled"
                    exit 0
                  fi
                done
                echo "missing skip git check"
                exit 1
                """)).handleAsync(job("codex_readonly", 5)).join();

        assertEquals(true, result.ok());
        assertEquals("skip git check enabled", result.message());
    }

    private AgentJobHandler handler(Path codexCliPath) {
        Config config = config(codexCliPath, tempDir, 5, 5);
        SessionService sessionService = new SessionService(config);
        return new AgentJobHandler(
                config,
                sessionService,
                new CodexService(config, new ProcessRunner(), sessionService, new HistoryService(sessionService.store())),
                Runnable::run
        );
    }

    private AgentRelayJobRequest job(String tool, int timeoutSeconds) {
        return new AgentRelayJobRequest("job_1", tool, "do work", timeoutSeconds);
    }

    private Path script(String content) throws Exception {
        Path script = tempDir.resolve("codex-" + Math.abs(content.hashCode()) + ".sh");
        Files.writeString(script, content);
        script.toFile().setExecutable(true);
        return script;
    }

    private Config config(Path codexCliPath, Path workingDirectory, int execMaxTimeout, int agentMaxTimeout) {
        return new Config(
                "127.0.0.1",
                8765,
                Optional.of("dev-token"),
                codexCliPath.toString(),
                tempDir.resolve("sessions.json").toString(),
                5,
                execMaxTimeout,
                false,
                1_000_000,
                AgentConfig.fromEnvironment(Map.of(
                        "CODEX_AGENT_STATE_FILE", tempDir.resolve("agent.json").toString(),
                        "CODEX_AGENT_WORKING_DIRECTORY", workingDirectory.toString(),
                        "CODEX_AGENT_JOB_MAX_TIMEOUT_SECONDS", String.valueOf(agentMaxTimeout)
                ), execMaxTimeout)
        );
    }

    private Config configUnchecked(Path codexCliPath, Path workingDirectory, int execMaxTimeout, int agentMaxTimeout) {
        return new Config(
                "127.0.0.1",
                8765,
                Optional.of("dev-token"),
                codexCliPath.toString(),
                tempDir.resolve("sessions.json").toString(),
                5,
                execMaxTimeout,
                false,
                1_000_000,
                new AgentConfig(
                        true,
                        Optional.of("https://emebridge.eagma.com"),
                        tempDir.resolve("agent.json").toString(),
                        "Codex Local Agent",
                        "codex-api-server/0.1.0-SNAPSHOT",
                        workingDirectory.toString(),
                        true,
                        true,
                        false,
                        30,
                        2,
                        60,
                        agentMaxTimeout,
                        "workspace-write"
                )
        );
    }
}
