package com.codexapi.server.session;

import com.codexapi.server.config.Config;

import java.nio.file.InvalidPathException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class SessionService {
    private final Config config;
    private final SessionStore sessionStore;

    public SessionService(Config config) {
        this(config, new SessionStore(config.sessionsFile()));
    }

    SessionService(Config config, SessionStore sessionStore) {
        this.config = config;
        this.sessionStore = sessionStore;
    }

    public Session create(CreateSessionRequest request) {
        if (request == null) {
            throw new SessionValidationException("Request body is required", "body");
        }

        String name = requiredString(request.name(), "name");
        String workingDirectory = requiredString(request.workingDirectory(), "workingDirectory");
        String resolvedWorkingDirectory = resolveWorkingDirectory(workingDirectory);
        int defaultTimeoutSeconds = defaultTimeoutSeconds(request.defaultTimeoutSeconds());

        String now = Instant.now().toString();
        Session session = new Session(
                generateSessionId(),
                name,
                resolvedWorkingDirectory,
                optionalString(request.description()),
                now,
                null,
                defaultTimeoutSeconds
        );

        return sessionStore.save(session);
    }

    public List<Session> list() {
        return sessionStore.list();
    }

    public Session get(String sessionId) {
        return sessionStore.find(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
    }

    public void delete(String sessionId) {
        if (!sessionStore.delete(sessionId)) {
            throw new SessionNotFoundException(sessionId);
        }
    }

    public Session touchLastUsedAt(String sessionId, String lastUsedAt) {
        Session session = get(sessionId);
        Session updatedSession = new Session(
                session.sessionId(),
                session.name(),
                session.workingDirectory(),
                session.description(),
                session.createdAt(),
                lastUsedAt,
                session.defaultTimeoutSeconds()
        );
        return sessionStore.save(updatedSession);
    }

    public SessionStore store() {
        return sessionStore;
    }

    private String requiredString(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new SessionValidationException(field + " is required", field);
        }
        return value.trim();
    }

    private String optionalString(String value) {
        return value == null ? "" : value.trim();
    }

    private String resolveWorkingDirectory(String workingDirectory) {
        Path path;
        try {
            path = Path.of(workingDirectory).toAbsolutePath().normalize();
        } catch (InvalidPathException exception) {
            throw new SessionValidationException("workingDirectory must be a valid path", "workingDirectory");
        }

        if (!Files.exists(path)) {
            throw new SessionValidationException("workingDirectory must exist", "workingDirectory");
        }
        if (!Files.isDirectory(path)) {
            throw new SessionValidationException("workingDirectory must be a directory", "workingDirectory");
        }

        return path.toString();
    }

    private int defaultTimeoutSeconds(Integer requestedTimeoutSeconds) {
        if (requestedTimeoutSeconds == null) {
            return config.execDefaultTimeoutSeconds();
        }
        if (requestedTimeoutSeconds <= 0) {
            throw new SessionValidationException("defaultTimeoutSeconds must be positive", "defaultTimeoutSeconds");
        }
        if (requestedTimeoutSeconds > config.execMaxTimeoutSeconds()) {
            throw new SessionValidationException(
                    "defaultTimeoutSeconds must not exceed CODEX_EXEC_MAX_TIMEOUT",
                    "defaultTimeoutSeconds"
            );
        }
        return requestedTimeoutSeconds;
    }

    private String generateSessionId() {
        return "s_" + UUID.randomUUID().toString().replace("-", "");
    }
}
