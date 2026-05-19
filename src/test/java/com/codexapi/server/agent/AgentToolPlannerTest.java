package com.codexapi.server.agent;

import com.codexapi.server.config.AgentConfig;
import com.codexapi.server.config.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AgentToolPlannerTest {
    @TempDir
    Path tempDir;

    @Test
    void mapsReadonlyVerifyAndChangeToExpectedExecutionPlan() {
        AgentToolPlanner planner = new AgentToolPlanner(config(120, 90));

        AgentToolExecutionPlan readonly = planner.plan(new AgentRelayJobRequest(
                "job_1",
                "codex_readonly",
                "inspect",
                30
        ));
        AgentToolExecutionPlan verify = planner.plan(new AgentRelayJobRequest(
                "job_2",
                "codex_verify",
                "test",
                30
        ));
        AgentToolExecutionPlan change = planner.plan(new AgentRelayJobRequest(
                "job_3",
                "codex_change",
                "change",
                30
        ));

        assertEquals("read-only", readonly.sandbox());
        assertEquals("workspace-write", verify.sandbox());
        assertEquals("workspace-write", change.sandbox());
        assertEquals("never", readonly.approvalPolicy());
        assertEquals("never", verify.approvalPolicy());
        assertEquals("never", change.approvalPolicy());
        assertEquals(true, readonly.ephemeral());
        assertTrue(readonly.prompt().contains("Tool mode: codex_readonly."));
        assertTrue(verify.prompt().contains("Verification step only."));
        assertTrue(change.prompt().contains("Make the smallest coherent repository change"));
    }

    @Test
    void usesConfiguredSandboxForMutableRelayTools() {
        AgentToolPlanner planner = new AgentToolPlanner(config(120, 90, "danger-full-access"));

        AgentToolExecutionPlan readonly = planner.plan(new AgentRelayJobRequest(
                "job_1",
                "codex_readonly",
                "inspect",
                30
        ));
        AgentToolExecutionPlan verify = planner.plan(new AgentRelayJobRequest(
                "job_2",
                "codex_verify",
                "test",
                30
        ));
        AgentToolExecutionPlan change = planner.plan(new AgentRelayJobRequest(
                "job_3",
                "codex_change",
                "change",
                30
        ));

        assertEquals("read-only", readonly.sandbox());
        assertEquals("danger-full-access", verify.sandbox());
        assertEquals("danger-full-access", change.sandbox());
    }

    @Test
    void usesConfiguredSandboxForReadonlyRelayTool() {
        AgentToolPlanner planner = new AgentToolPlanner(config(
                120,
                90,
                "danger-full-access",
                "danger-full-access"
        ));

        AgentToolExecutionPlan readonly = planner.plan(new AgentRelayJobRequest(
                "job_1",
                "codex_readonly",
                "inspect",
                30
        ));

        assertEquals("danger-full-access", readonly.sandbox());
    }

    @Test
    void capsTimeoutByAgentAndExecMax() {
        AgentToolPlanner planner = new AgentToolPlanner(config(120, 90));

        AgentToolExecutionPlan requestedTooHigh = planner.plan(new AgentRelayJobRequest(
                "job_1",
                "codex_change",
                "change",
                500
        ));
        AgentToolExecutionPlan noTimeout = planner.plan(new AgentRelayJobRequest(
                "job_2",
                "codex_change",
                "change",
                null
        ));

        assertEquals(90, requestedTooHigh.timeoutSeconds());
        assertEquals(90, noTimeout.timeoutSeconds());
    }

    @Test
    void rejectsReadLogAndUnknownToolsAsUnsupported() {
        AgentToolPlanner planner = new AgentToolPlanner(config(120, 90));

        AgentToolPlanningException readLog = assertThrows(
                AgentToolPlanningException.class,
                () -> planner.plan(new AgentRelayJobRequest("job_1", "codex_read_log", "log", 30))
        );
        AgentToolPlanningException unknown = assertThrows(
                AgentToolPlanningException.class,
                () -> planner.plan(new AgentRelayJobRequest("job_2", "unknown", "x", 30))
        );

        assertEquals("UNSUPPORTED_TOOL", readLog.errorCode());
        assertEquals("UNSUPPORTED_TOOL", unknown.errorCode());
    }

    @Test
    void rejectsInvalidTimeoutAndMissingMessageForSupportedTools() {
        AgentToolPlanner planner = new AgentToolPlanner(config(120, 90));

        AgentToolPlanningException invalidTimeout = assertThrows(
                AgentToolPlanningException.class,
                () -> planner.plan(new AgentRelayJobRequest("job_1", "codex_change", "change", 0))
        );
        AgentToolPlanningException missingMessage = assertThrows(
                AgentToolPlanningException.class,
                () -> planner.plan(new AgentRelayJobRequest("job_2", "codex_verify", " ", 30))
        );

        assertEquals("INVALID_JOB_REQUEST", invalidTimeout.errorCode());
        assertEquals("INVALID_JOB_REQUEST", missingMessage.errorCode());
    }

    private Config config(int execMaxTimeoutSeconds, int agentMaxTimeoutSeconds) {
        return config(execMaxTimeoutSeconds, agentMaxTimeoutSeconds, "workspace-write");
    }

    private Config config(int execMaxTimeoutSeconds, int agentMaxTimeoutSeconds, String sandboxMode) {
        return config(execMaxTimeoutSeconds, agentMaxTimeoutSeconds, sandboxMode, "read-only");
    }

    private Config config(
            int execMaxTimeoutSeconds,
            int agentMaxTimeoutSeconds,
            String sandboxMode,
            String readonlySandboxMode
    ) {
        return new Config(
                "127.0.0.1",
                8765,
                Optional.of("dev-token"),
                "codex",
                tempDir.resolve("sessions.json").toString(),
                60,
                execMaxTimeoutSeconds,
                false,
                1_000_000,
                AgentConfig.fromEnvironment(Map.of(
                        "CODEX_AGENT_STATE_FILE", tempDir.resolve("agent.json").toString(),
                        "CODEX_AGENT_WORKING_DIRECTORY", tempDir.toString(),
                        "CODEX_AGENT_JOB_MAX_TIMEOUT_SECONDS", String.valueOf(agentMaxTimeoutSeconds),
                        "CODEX_AGENT_SANDBOX_MODE", sandboxMode,
                        "CODEX_AGENT_READONLY_SANDBOX_MODE", readonlySandboxMode
                ), execMaxTimeoutSeconds)
        );
    }
}
