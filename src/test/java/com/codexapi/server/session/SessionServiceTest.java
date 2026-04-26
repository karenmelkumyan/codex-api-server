package com.codexapi.server.session;

import com.codexapi.server.config.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SessionServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void createsSessionWithNormalizedAbsoluteWorkingDirectory() {
        Path projectDir = createDirectory("project");
        SessionService service = service();

        Session session = service.create(new CreateSessionRequest("project", projectDir.toString() + "/.", "", null));

        assertEquals(projectDir.toAbsolutePath().normalize().toString(), session.workingDirectory());
    }

    @Test
    void rejectsMissingName() {
        SessionValidationException exception = assertThrows(
                SessionValidationException.class,
                () -> service().create(new CreateSessionRequest(" ", tempDir.toString(), "", null))
        );

        assertEquals("name", exception.details().get("field"));
    }

    @Test
    void rejectsMissingWorkingDirectory() {
        SessionValidationException exception = assertThrows(
                SessionValidationException.class,
                () -> service().create(new CreateSessionRequest("project", " ", "", null))
        );

        assertEquals("workingDirectory", exception.details().get("field"));
    }

    @Test
    void rejectsNonExistingWorkingDirectory() {
        SessionValidationException exception = assertThrows(
                SessionValidationException.class,
                () -> service().create(new CreateSessionRequest("project", tempDir.resolve("missing").toString(), "", null))
        );

        assertEquals("workingDirectory", exception.details().get("field"));
    }

    @Test
    void persistsSessionsAndReloadsThem() {
        Path projectDir = createDirectory("project");
        Config config = config();
        Session created = new SessionService(config).create(new CreateSessionRequest("project", projectDir.toString(), "", null));

        SessionService reloadedService = new SessionService(config);

        assertEquals(created, reloadedService.get(created.sessionId()));
    }

    @Test
    void deletesSessionWithoutDeletingProjectFiles() throws Exception {
        Path projectDir = createDirectory("project");
        Path projectFile = projectDir.resolve("keep.txt");
        Files.writeString(projectFile, "keep");
        SessionService service = service();
        Session session = service.create(new CreateSessionRequest("project", projectDir.toString(), "", null));

        service.delete(session.sessionId());

        assertThrows(SessionNotFoundException.class, () -> service.get(session.sessionId()));
        assertTrue(Files.exists(projectFile));
    }

    private Path createDirectory(String name) {
        try {
            return Files.createDirectories(tempDir.resolve(name));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private SessionService service() {
        return new SessionService(config());
    }

    private Config config() {
        return new Config(
                "127.0.0.1",
                8765,
                Optional.of("dev-token"),
                "codex",
                tempDir.resolve("sessions.json").toString(),
                600,
                1800,
                false,
                1_000_000
        );
    }
}
