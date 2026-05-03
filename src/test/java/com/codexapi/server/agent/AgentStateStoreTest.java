package com.codexapi.server.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AgentStateStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void savesAndReloadsState() {
        Path stateFile = tempDir.resolve("nested").resolve("agent-state.json");
        AgentState state = state();

        AgentStateStore store = new AgentStateStore(stateFile.toString());
        store.save(state);

        AgentStateStore reloaded = new AgentStateStore(stateFile.toString());

        assertEquals(state, reloaded.get().orElseThrow());
        assertTrue(Files.exists(stateFile));
    }

    @Test
    void toStringDoesNotLeakAgentSecret() {
        String rendered = state().toString();

        assertFalse(rendered.contains("super-secret"));
        assertTrue(rendered.contains("agentSecretPresent=true"));
    }

    @Test
    void invalidExistingStateFailsClearlyAndIsNotOverwritten() throws Exception {
        Path stateFile = tempDir.resolve("agent-state.json");
        String invalidJson = "{ not valid json";
        Files.writeString(stateFile, invalidJson);

        AgentStateStoreException exception = assertThrows(
                AgentStateStoreException.class,
                () -> new AgentStateStore(stateFile.toString())
        );

        assertTrue(exception.getMessage().contains("Unable to load agent state file"));
        assertTrue(exception.getMessage().contains("Delete CODEX_AGENT_STATE_FILE to reset connector identity."));
        assertEquals(invalidJson, Files.readString(stateFile));
    }

    @Test
    void saveRefusesToOverwriteStateThatBecameInvalidAfterLoad() throws Exception {
        Path stateFile = tempDir.resolve("agent-state.json");
        AgentStateStore store = new AgentStateStore(stateFile.toString());
        store.save(state());

        String invalidJson = "{ still not valid json";
        Files.writeString(stateFile, invalidJson);

        assertThrows(AgentStateStoreException.class, () -> store.save(state().withLastConnectedAt("2026-05-02T00:00:00Z")));
        assertEquals(invalidJson, Files.readString(stateFile));
    }

    @Test
    void usesOwnerOnlyFilePermissionsWhenSupported() throws Exception {
        Path stateFile = tempDir.resolve("private").resolve("agent-state.json");

        new AgentStateStore(stateFile.toString()).save(state());

        if (Files.getFileStore(stateFile).supportsFileAttributeView("posix")) {
            Set<PosixFilePermission> expectedFilePermissions = PosixFilePermissions.fromString("rw-------");
            Set<PosixFilePermission> expectedDirectoryPermissions = PosixFilePermissions.fromString("rwx------");

            assertEquals(expectedFilePermissions, Files.getPosixFilePermissions(stateFile));
            assertEquals(expectedDirectoryPermissions, Files.getPosixFilePermissions(stateFile.getParent()));
        }
    }

    private AgentState state() {
        return new AgentState(
                "ag_test",
                "super-secret",
                "codex",
                "https://bridge.example.test",
                "wss://bridge.example.test/agent/ws",
                "Dev Laptop",
                "2026-05-02T00:00:00Z",
                null
        );
    }
}
