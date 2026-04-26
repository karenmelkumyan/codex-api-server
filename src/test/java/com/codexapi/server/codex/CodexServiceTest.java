package com.codexapi.server.codex;

import com.codexapi.server.config.Config;
import com.codexapi.server.history.HistoryService;
import com.codexapi.server.http.ApiException;
import com.codexapi.server.process.ProcessRunner;
import com.codexapi.server.session.Session;
import com.codexapi.server.session.SessionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CodexServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void buildsCommandWithConfigApprovalPolicyAndStdinMarker() {
        CodexService service = service();
        Session session = session();
        CodexExecRequest request = new CodexExecRequest(
                "secret prompt text",
                120,
                "gpt-5.4",
                "default",
                "read-only",
                "never",
                true,
                false
        );

        CodexCommand command = service.buildCommand(session, request, tempDir.resolve("last-message.txt"));

        assertEquals("codex", command.executable());
        assertTrue(command.args().contains("exec"));
        assertTrue(command.args().contains("--cd"));
        assertTrue(command.args().contains(session.workingDirectory()));
        assertTrue(command.args().contains("--sandbox"));
        assertTrue(command.args().contains("read-only"));
        assertTrue(command.args().contains("-c"));
        assertTrue(command.args().contains("approval_policy=\"never\""));
        assertTrue(command.args().contains("--ephemeral"));
        assertEquals("-", command.args().get(command.args().size() - 1));
        assertFalse(command.args().contains("secret prompt text"));
        assertFalse(command.args().contains("--ask-for-approval"));
    }

    @Test
    void rejectsInvalidSandbox() {
        CodexService service = service();
        CodexExecRequest request = new CodexExecRequest("test", null, null, null, "invalid", null, null, null);

        ApiException exception = assertThrows(
                ApiException.class,
                () -> service.buildCommand(session(), request, tempDir.resolve("last-message.txt"))
        );

        assertEquals("VALIDATION_ERROR", exception.code());
        assertEquals("sandbox", exception.details().get("field"));
    }

    @Test
    void acceptsWorkspaceWriteSandbox() {
        CodexService service = service();
        CodexExecRequest request = new CodexExecRequest("test", null, null, null, "workspace-write", null, null, null);

        CodexCommand command = service.buildCommand(session(), request, tempDir.resolve("last-message.txt"));

        assertTrue(command.args().contains("workspace-write"));
    }

    @Test
    void rejectsDangerFullAccessSandbox() {
        CodexService service = service();
        CodexExecRequest request = new CodexExecRequest(
                "test",
                null,
                null,
                null,
                "danger-" + "full-access",
                null,
                null,
                null
        );

        ApiException exception = assertThrows(
                ApiException.class,
                () -> service.buildCommand(session(), request, tempDir.resolve("last-message.txt"))
        );

        assertEquals("VALIDATION_ERROR", exception.code());
        assertEquals("sandbox", exception.details().get("field"));
    }

    @Test
    void rejectsInvalidApprovalPolicy() {
        CodexService service = service();
        CodexExecRequest request = new CodexExecRequest("test", null, null, null, "read-only", "always", null, null);

        ApiException exception = assertThrows(
                ApiException.class,
                () -> service.buildCommand(session(), request, tempDir.resolve("last-message.txt"))
        );

        assertEquals("VALIDATION_ERROR", exception.code());
        assertEquals("approvalPolicy", exception.details().get("field"));
    }

    @Test
    void mapsAllowedApprovalPoliciesExactly() {
        CodexService service = service();

        assertTrue(argsForApprovalPolicy(service, "untrusted").contains("approval_policy=\"untrusted\""));
        assertTrue(argsForApprovalPolicy(service, "on-request").contains("approval_policy=\"on-request\""));
        assertTrue(argsForApprovalPolicy(service, "never").contains("approval_policy=\"never\""));
    }

    @Test
    void doesNotStorePromptTextInHistory() throws IOException {
        Path fakeCodex = tempDir.resolve("fake-codex");
        Files.writeString(fakeCodex, """
                #!/usr/bin/env bash
                output_file=""
                while [ "$#" -gt 0 ]; do
                  if [ "$1" = "--output-last-message" ]; then
                    shift
                    output_file="$1"
                  fi
                  shift || true
                done
                cat >/dev/null
                printf 'fake final answer' > "$output_file"
                """, StandardCharsets.UTF_8);
        assertTrue(fakeCodex.toFile().setExecutable(true));

        Config config = config(fakeCodex.toString());
        SessionService sessionService = new SessionService(config);
        HistoryService historyService = new HistoryService(sessionService.store());
        CodexService service = new CodexService(config, new ProcessRunner(), sessionService, historyService);
        Session session = sessionService.create(new com.codexapi.server.session.CreateSessionRequest(
                "test",
                tempDir.toString(),
                "",
                600
        ));

        service.execute(session, new CodexExecRequest(
                "do not persist this prompt",
                30,
                null,
                null,
                "read-only",
                "never",
                true,
                false
        ));

        List<com.codexapi.server.history.ExecutionRecord> history = historyService.list(session.sessionId());
        assertEquals(1, history.size());
        assertEquals("[prompt omitted]", history.get(0).promptPreview());
        assertFalse(history.get(0).promptPreview().contains("do not persist this prompt"));
    }

    private java.util.List<String> argsForApprovalPolicy(CodexService service, String approvalPolicy) {
        CodexExecRequest request = new CodexExecRequest("test", null, null, null, "read-only", approvalPolicy, null, null);
        return service.buildCommand(session(), request, tempDir.resolve(approvalPolicy + ".txt")).args();
    }

    private CodexService service() {
        Config config = config("codex");
        SessionService sessionService = new SessionService(config);
        return new CodexService(
                config,
                new ProcessRunner(),
                sessionService,
                new HistoryService(sessionService.store())
        );
    }

    private Config config(String codexCliPath) {
        return new Config(
                "127.0.0.1",
                8765,
                Optional.of("dev-token"),
                codexCliPath,
                tempDir.resolve("sessions.json").toString(),
                600,
                1800,
                false,
                1_000_000
        );
    }

    private Session session() {
        return new Session(
                "s_test",
                "test",
                tempDir.toString(),
                "",
                "2026-01-01T00:00:00Z",
                null,
                600
        );
    }
}
