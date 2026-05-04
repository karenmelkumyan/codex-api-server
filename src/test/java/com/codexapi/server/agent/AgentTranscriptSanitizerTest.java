package com.codexapi.server.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AgentTranscriptSanitizerTest {
    private final AgentTranscriptSanitizer sanitizer = new AgentTranscriptSanitizer();

    @Test
    void redactsSecretsToolUrlsCommandsAndSensitivePathPrefixes() {
        AgentTranscriptSanitizer.SanitizedTranscriptContent content = sanitizer.sanitize("""
                authorization: Bearer raw-token
                bridgeKey=bridge-secret
                {"CODEX_API_TOKEN":"codex-token"}
                https://bridge.example.test/tools/bridge-key/codex_change
                wss://bridge.example.test/agent/ws?token=secret
                codex exec --api-key secret --cd /Users/karenmelkumyan/Documents/GitHub/codex-api-server
                opened /Users/karenmelkumyan/Documents/GitHub/codex-api-server/src/Main.java
                temp /private/var/folders/aa/bb/secret
                standalone-agent-secret
                """, false, List.of("standalone-agent-secret"));

        assertTrue(content.redacted());
        assertFalse(content.truncated());
        assertTrue(content.content().contains("authorization=[redacted]"));
        assertTrue(content.content().contains("bridgeKey=[redacted]"));
        assertTrue(content.content().contains("\"CODEX_API_TOKEN\":\"[redacted]\""));
        assertTrue(content.content().contains("[redacted tool url]"));
        assertTrue(content.content().contains("[redacted command]"));
        assertTrue(content.content().contains("[local path]/codex-api-server/src/Main.java"));
        assertFalse(content.content().contains("raw-token"));
        assertFalse(content.content().contains("bridge-secret"));
        assertFalse(content.content().contains("codex-token"));
        assertFalse(content.content().contains("standalone-agent-secret"));
        assertFalse(content.content().contains("/Users/karenmelkumyan"));
        assertFalse(content.content().contains("/private/var/folders"));
        assertFalse(content.content().contains("https://bridge.example.test/tools"));
        assertFalse(content.content().contains("wss://bridge.example.test/agent/ws"));
    }
}
