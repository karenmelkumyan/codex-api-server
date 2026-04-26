package com.codexapi.server.session;

import com.codexapi.server.history.ExecutionRecord;
import com.codexapi.server.util.JsonUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class SessionStore {
    private final Path sessionsFile;
    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private final Map<String, List<ExecutionRecord>> historyBySessionId = new LinkedHashMap<>();

    public SessionStore(String sessionsFile) {
        this.sessionsFile = Path.of(sessionsFile).toAbsolutePath().normalize();
        load();
    }

    public synchronized List<Session> list() {
        return new ArrayList<>(sessions.values());
    }

    public synchronized Optional<Session> find(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    public synchronized Session save(Session session) {
        sessions.put(session.sessionId(), session);
        persist();
        return session;
    }

    public synchronized List<ExecutionRecord> history(String sessionId) {
        return new ArrayList<>(historyBySessionId.getOrDefault(sessionId, List.of()));
    }

    public synchronized void addExecutionRecord(ExecutionRecord record) {
        List<ExecutionRecord> history = new ArrayList<>(historyBySessionId.getOrDefault(record.sessionId(), List.of()));
        history.add(record);
        historyBySessionId.put(record.sessionId(), history);
        persist();
    }

    public synchronized boolean delete(String sessionId) {
        Session removed = sessions.remove(sessionId);
        if (removed == null) {
            return false;
        }
        historyBySessionId.remove(sessionId);
        persist();
        return true;
    }

    private void load() {
        if (!Files.exists(sessionsFile)) {
            return;
        }

        try (var inputStream = Files.newInputStream(sessionsFile)) {
            SessionFile sessionFile = JsonUtil.fromJson(inputStream, SessionFile.class);
            for (Session session : sessionFile.sessions()) {
                sessions.put(session.sessionId(), session);
            }
            for (ExecutionRecord record : sessionFile.history()) {
                historyBySessionId.computeIfAbsent(record.sessionId(), ignored -> new ArrayList<>()).add(record);
            }
        } catch (IOException exception) {
            throw new SessionStorageException("Unable to load sessions file", exception);
        }
    }

    private void persist() {
        Path parent = sessionsFile.getParent();
        try {
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            Path tempFile = Files.createTempFile(parent, "sessions-", ".tmp");
            try {
                Files.writeString(
                        tempFile,
                        JsonUtil.toJson(new SessionFile(List.copyOf(sessions.values()), allHistory())),
                        StandardCharsets.UTF_8
                );
                moveIntoPlace(tempFile);
            } finally {
                Files.deleteIfExists(tempFile);
            }
        } catch (IOException exception) {
            throw new SessionStorageException("Unable to persist sessions file", exception);
        }
    }

    private List<ExecutionRecord> allHistory() {
        List<ExecutionRecord> records = new ArrayList<>();
        for (List<ExecutionRecord> history : historyBySessionId.values()) {
            records.addAll(history);
        }
        return records;
    }

    private void moveIntoPlace(Path tempFile) throws IOException {
        try {
            Files.move(tempFile, sessionsFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(tempFile, sessionsFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
