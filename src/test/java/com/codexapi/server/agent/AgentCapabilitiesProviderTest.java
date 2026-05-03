package com.codexapi.server.agent;

import com.codexapi.server.config.AgentConfig;
import com.codexapi.server.config.Config;
import com.codexapi.server.process.ProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class AgentCapabilitiesProviderTest {
    @TempDir
    Path tempDir;

    @Test
    void returnsSafeCodexCliCapabilities() throws Exception {
        Path fakeCodex = tempDir.resolve("fake-codex");
        Files.writeString(fakeCodex, "#!/bin/sh\necho codex-cli 1.2.3\n");
        fakeCodex.toFile().setExecutable(true);

        Map<String, Object> capabilities = new AgentCapabilitiesProvider(
                config(fakeCodex.toString()),
                new ProcessRunner()
        ).capabilities();

        assertEquals(true, capabilities.get("codexCliAvailable"));
        assertEquals("codex-cli 1.2.3", capabilities.get("codexCliVersion"));
        assertEquals(true, capabilities.get("supportsReadonly"));
        assertEquals(true, capabilities.get("supportsVerify"));
        assertEquals(true, capabilities.get("supportsChange"));
        assertEquals(true, capabilities.get("workingDirectoryConfigured"));
        assertFalse(capabilities.toString().contains(tempDir.toString()));
    }

    @Test
    void reportsUnavailableCodexCliWithoutVersion() {
        Map<String, Object> capabilities = new AgentCapabilitiesProvider(
                config(tempDir.resolve("missing-codex").toString()),
                new ProcessRunner()
        ).capabilities();

        assertEquals(false, capabilities.get("codexCliAvailable"));
        assertFalse(capabilities.containsKey("codexCliVersion"));
    }

    private Config config(String codexCliPath) {
        return new Config(
                "127.0.0.1",
                8765,
                Optional.of("dev-token"),
                codexCliPath,
                tempDir.resolve("sessions.json").toString(),
                5,
                900,
                false,
                1_000_000,
                AgentConfig.fromEnvironment(Map.of(
                        "CODEX_AGENT_STATE_FILE", tempDir.resolve("agent.json").toString(),
                        "CODEX_AGENT_WORKING_DIRECTORY", tempDir.toString()
                ), 900)
        );
    }
}
