package com.codexapi.server.agent;

import com.codexapi.server.util.JsonUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.Set;

public final class AgentStateStore {
    private static final Set<PosixFilePermission> OWNER_ONLY_FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> OWNER_ONLY_DIRECTORY_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");

    private final Path stateFile;
    private Optional<AgentState> state;

    public AgentStateStore(String stateFile) {
        this.stateFile = Path.of(stateFile).toAbsolutePath().normalize();
        this.state = load();
    }

    public synchronized Optional<AgentState> get() {
        return state;
    }

    public synchronized AgentState save(AgentState newState) {
        verifyExistingStateIsReadable();
        persist(newState);
        state = Optional.of(newState);
        return newState;
    }

    public Path stateFile() {
        return stateFile;
    }

    private Optional<AgentState> load() {
        if (!Files.exists(stateFile)) {
            return Optional.empty();
        }
        return Optional.of(readExistingState());
    }

    private void verifyExistingStateIsReadable() {
        if (Files.exists(stateFile)) {
            readExistingState();
        }
    }

    private AgentState readExistingState() {
        try (var inputStream = Files.newInputStream(stateFile)) {
            return JsonUtil.fromJson(inputStream, AgentState.class);
        } catch (IOException | IllegalArgumentException exception) {
            throw new AgentStateStoreException(
                    "Unable to load agent state file '" + stateFile + "'. Delete CODEX_AGENT_STATE_FILE to reset connector identity.",
                    exception
            );
        }
    }

    private void persist(AgentState newState) {
        Path parent = stateFile.getParent();
        try {
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
                setOwnerOnlyDirectoryPermissions(parent);
            }

            Path tempFile = parent == null
                    ? Files.createTempFile("agent-state-", ".tmp")
                    : Files.createTempFile(parent, "agent-state-", ".tmp");
            try {
                setOwnerOnlyFilePermissions(tempFile);
                Files.writeString(tempFile, JsonUtil.toJson(newState), StandardCharsets.UTF_8);
                setOwnerOnlyFilePermissions(tempFile);
                moveIntoPlace(tempFile);
                setOwnerOnlyFilePermissions(stateFile);
            } finally {
                Files.deleteIfExists(tempFile);
            }
        } catch (IOException exception) {
            throw new AgentStateStoreException("Unable to persist agent state file '" + stateFile + "'.", exception);
        }
    }

    private void moveIntoPlace(Path tempFile) throws IOException {
        try {
            Files.move(tempFile, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(tempFile, stateFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void setOwnerOnlyFilePermissions(Path path) throws IOException {
        if (supportsPosixPermissions(path)) {
            Files.setPosixFilePermissions(path, OWNER_ONLY_FILE_PERMISSIONS);
        }
    }

    private void setOwnerOnlyDirectoryPermissions(Path path) throws IOException {
        if (supportsPosixPermissions(path)) {
            Files.setPosixFilePermissions(path, OWNER_ONLY_DIRECTORY_PERMISSIONS);
        }
    }

    private boolean supportsPosixPermissions(Path path) throws IOException {
        FileStore fileStore = Files.getFileStore(path);
        return fileStore.supportsFileAttributeView("posix");
    }
}
