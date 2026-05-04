package com.codexapi.server.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ConfigTest {
    @TempDir
    Path tempDir;

    @Test
    void defaultsToLoopbackHost() {
        Config config = Config.fromEnvironment(Map.of());

        assertEquals("127.0.0.1", config.host());
        assertEquals(1800, config.execDefaultTimeoutSeconds());
        assertEquals(7200, config.execMaxTimeoutSeconds());
        assertEquals(1_000_000, config.maxRequestBytes());
        assertTrue(config.agent().enabled());
        assertTrue(config.agent().active());
        assertEquals("https://emebridge.eagma.com", config.agent().bridgeBaseUrl().orElseThrow());
        assertTrue(config.agent().pairOnStart());
        assertEquals(7200, config.agent().jobMaxTimeoutSeconds());
        assertEquals(
                Path.of(System.getProperty("user.home"), ".codex", "eme-codex-agent", "agent.json").toString(),
                config.agent().stateFile()
        );
        assertTrue(config.agent().inactiveReason().isEmpty());
    }

    @Test
    void rejectsRemoteBindUnlessAllowed() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> Config.fromEnvironment(Map.of("CODEX_API_HOST", "0.0.0.0"))
        );

        assertEquals(
                "Refusing to bind to non-local host '0.0.0.0' unless CODEX_ALLOW_REMOTE_BIND=true",
                exception.getMessage()
        );
    }

    @Test
    void allowsRemoteBindWhenExplicitlyEnabled() {
        Config config = Config.fromEnvironment(Map.of(
                "CODEX_API_HOST", "0.0.0.0",
                "CODEX_ALLOW_REMOTE_BIND", "true"
        ));

        assertEquals("0.0.0.0", config.host());
    }

    @Test
    void rejectsInvalidMaxRequestBytes() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> Config.fromEnvironment(Map.of("CODEX_MAX_REQUEST_BYTES", "0"))
        );

        assertEquals("CODEX_MAX_REQUEST_BYTES must be greater than zero", exception.getMessage());
    }

    @Test
    void rejectsInvalidExecMaxTimeoutWithOriginalConfigError() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> Config.fromEnvironment(Map.of("CODEX_EXEC_MAX_TIMEOUT", "0"))
        );

        assertEquals("CODEX_EXEC_MAX_TIMEOUT must be greater than zero", exception.getMessage());
    }

    @Test
    void agentCanBeDisabledDespiteDefaultBridgeUrl() {
        Config config = Config.fromEnvironment(Map.of("CODEX_AGENT_ENABLED", "false"));

        assertFalse(config.agent().enabled());
        assertFalse(config.agent().active());
        assertEquals(
                "Connector mode is disabled.",
                config.agent().inactiveReason().orElseThrow()
        );
    }

    @Test
    void agentWithoutBridgeUrlReportsInactive() {
        AgentConfig config = new AgentConfig(
                true,
                Optional.empty(),
                "./data/agent.json",
                "Codex Local Agent",
                "codex-api-server/0.1.0-SNAPSHOT",
                tempDir.toAbsolutePath().normalize().toString(),
                true,
                true,
                false,
                30,
                2,
                60,
                1800,
                "workspace-write"
        );

        assertFalse(config.active());
        assertEquals("CODEX_AGENT_BRIDGE_BASE_URL is not configured.", config.inactiveReason().orElseThrow());
    }

    @Test
    void parsesAgentConfigValues() {
        Config config = Config.fromEnvironment(Map.ofEntries(
                Map.entry("CODEX_AGENT_ENABLED", "true"),
                Map.entry("CODEX_AGENT_BRIDGE_BASE_URL", "https://bridge.example.test/"),
                Map.entry("CODEX_AGENT_STATE_FILE", tempDir.resolve("state.json").toString()),
                Map.entry("CODEX_AGENT_DISPLAY_NAME", "Dev Laptop"),
                Map.entry("CODEX_AGENT_CLIENT_VERSION", "test-client/1"),
                Map.entry("CODEX_AGENT_WORKING_DIRECTORY", tempDir.toString() + "/."),
                Map.entry("CODEX_AGENT_AUTO_BOOTSTRAP", "false"),
                Map.entry("CODEX_AGENT_AUTO_PAIR_ON_FIRST_BOOTSTRAP", "false"),
                Map.entry("CODEX_AGENT_PAIR_ON_START", "true"),
                Map.entry("CODEX_AGENT_HEARTBEAT_INTERVAL_SECONDS", "15"),
                Map.entry("CODEX_AGENT_RECONNECT_INITIAL_SECONDS", "3"),
                Map.entry("CODEX_AGENT_RECONNECT_MAX_SECONDS", "45"),
                Map.entry("CODEX_AGENT_JOB_MAX_TIMEOUT_SECONDS", "900"),
                Map.entry("CODEX_AGENT_SANDBOX_MODE", "danger-full-access")
        ));

        AgentConfig agent = config.agent();

        assertTrue(agent.active());
        assertEquals("https://bridge.example.test", agent.bridgeBaseUrl().orElseThrow());
        assertEquals(tempDir.resolve("state.json").toString(), agent.stateFile());
        assertEquals("Dev Laptop", agent.displayName());
        assertEquals("test-client/1", agent.clientVersion());
        assertEquals(tempDir.toAbsolutePath().normalize().toString(), agent.workingDirectory());
        assertFalse(agent.autoBootstrap());
        assertFalse(agent.autoPairOnFirstBootstrap());
        assertTrue(agent.pairOnStart());
        assertEquals(15, agent.heartbeatIntervalSeconds());
        assertEquals(3, agent.reconnectInitialSeconds());
        assertEquals(45, agent.reconnectMaxSeconds());
        assertEquals(900, agent.jobMaxTimeoutSeconds());
        assertEquals("danger-full-access", agent.sandboxMode());
        assertTrue(agent.inactiveReason().isEmpty());
    }

    @Test
    void rejectsInvalidAgentSandboxMode() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> Config.fromEnvironment(Map.of("CODEX_AGENT_SANDBOX_MODE", "read-only"))
        );

        assertEquals(
                "CODEX_AGENT_SANDBOX_MODE must be one of: workspace-write, danger-full-access",
                exception.getMessage()
        );
    }

    @Test
    void rejectsInvalidAgentTimingConfig() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> Config.fromEnvironment(Map.of(
                        "CODEX_AGENT_RECONNECT_INITIAL_SECONDS", "30",
                        "CODEX_AGENT_RECONNECT_MAX_SECONDS", "10"
                ))
        );

        assertEquals(
                "CODEX_AGENT_RECONNECT_INITIAL_SECONDS must not exceed CODEX_AGENT_RECONNECT_MAX_SECONDS",
                exception.getMessage()
        );
    }

    @Test
    void rejectsInvalidAgentBridgeUrl() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> Config.fromEnvironment(Map.of("CODEX_AGENT_BRIDGE_BASE_URL", "ftp://bridge.example.test"))
        );

        assertEquals("CODEX_AGENT_BRIDGE_BASE_URL must be an http(s) URL", exception.getMessage());
    }

    @Test
    void agentJobMaxTimeoutDefaultsToExecMaxTimeout() {
        Config config = Config.fromEnvironment(Map.of("CODEX_EXEC_MAX_TIMEOUT", "7200"));

        assertEquals(7200, config.execMaxTimeoutSeconds());
        assertEquals(7200, config.agent().jobMaxTimeoutSeconds());
    }

    @Test
    void activeAgentRejectsMissingWorkingDirectory() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> Config.fromEnvironment(Map.of(
                        "CODEX_AGENT_ENABLED", "true",
                        "CODEX_AGENT_BRIDGE_BASE_URL", "https://bridge.example.test",
                        "CODEX_AGENT_WORKING_DIRECTORY", tempDir.resolve("missing").toString()
                ))
        );

        assertEquals(
                "CODEX_AGENT_WORKING_DIRECTORY must exist and be a directory when connector mode is active",
                exception.getMessage()
        );
    }

    @Test
    void inactiveAgentAllowsMissingWorkingDirectory() {
        Config config = Config.fromEnvironment(Map.of(
                "CODEX_AGENT_ENABLED", "false",
                "CODEX_AGENT_WORKING_DIRECTORY", tempDir.resolve("missing").toString()
        ));

        assertFalse(config.agent().active());
    }
}
