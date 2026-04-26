package com.codexapi.server.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ConfigTest {
    @Test
    void defaultsToLoopbackHost() {
        Config config = Config.fromEnvironment(Map.of());

        assertEquals("127.0.0.1", config.host());
        assertEquals(1_000_000, config.maxRequestBytes());
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
}
