package com.codexapi.server.config;

import java.util.Map;
import java.util.Optional;

public record Config(
        String host,
        int port,
        Optional<String> apiToken,
        String codexCliPath,
        String sessionsFile,
        int execDefaultTimeoutSeconds,
        int execMaxTimeoutSeconds,
        boolean allowRemoteBind,
        int maxRequestBytes,
        AgentConfig agent
) {
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 8765;
    private static final String DEFAULT_CODEX_CLI_PATH = "codex";
    private static final String DEFAULT_SESSIONS_FILE = "./data/sessions.json";
    private static final int DEFAULT_EXEC_DEFAULT_TIMEOUT_SECONDS = 600;
    private static final int DEFAULT_EXEC_MAX_TIMEOUT_SECONDS = 1800;
    private static final boolean DEFAULT_ALLOW_REMOTE_BIND = false;
    private static final int DEFAULT_MAX_REQUEST_BYTES = 1_000_000;

    public static Config fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    static Config fromEnvironment(Map<String, String> env) {
        int execDefaultTimeoutSeconds = intValue(
                env,
                "CODEX_EXEC_DEFAULT_TIMEOUT",
                DEFAULT_EXEC_DEFAULT_TIMEOUT_SECONDS
        );
        int execMaxTimeoutSeconds = intValue(env, "CODEX_EXEC_MAX_TIMEOUT", DEFAULT_EXEC_MAX_TIMEOUT_SECONDS);
        if (execMaxTimeoutSeconds < 1) {
            throw new IllegalArgumentException("CODEX_EXEC_MAX_TIMEOUT must be greater than zero");
        }

        Config config = new Config(
                stringValue(env, "CODEX_API_HOST", DEFAULT_HOST),
                intValue(env, "CODEX_API_PORT", DEFAULT_PORT),
                optionalStringValue(env, "CODEX_API_TOKEN"),
                stringValue(env, "CODEX_CLI_PATH", DEFAULT_CODEX_CLI_PATH),
                stringValue(env, "CODEX_SESSIONS_FILE", DEFAULT_SESSIONS_FILE),
                execDefaultTimeoutSeconds,
                execMaxTimeoutSeconds,
                booleanValue(env, "CODEX_ALLOW_REMOTE_BIND", DEFAULT_ALLOW_REMOTE_BIND),
                intValue(env, "CODEX_MAX_REQUEST_BYTES", DEFAULT_MAX_REQUEST_BYTES),
                AgentConfig.fromEnvironment(env, execMaxTimeoutSeconds)
        );

        config.validate();
        return config;
    }

    private void validate() {
        if (host.isBlank()) {
            throw new IllegalArgumentException("CODEX_API_HOST must not be blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("CODEX_API_PORT must be between 1 and 65535");
        }
        if (!allowRemoteBind && !isLocalHost(host)) {
            throw new IllegalArgumentException(
                    "Refusing to bind to non-local host '" + host + "' unless CODEX_ALLOW_REMOTE_BIND=true"
            );
        }
        if (codexCliPath.isBlank()) {
            throw new IllegalArgumentException("CODEX_CLI_PATH must not be blank");
        }
        if (sessionsFile.isBlank()) {
            throw new IllegalArgumentException("CODEX_SESSIONS_FILE must not be blank");
        }
        if (execDefaultTimeoutSeconds < 1) {
            throw new IllegalArgumentException("CODEX_EXEC_DEFAULT_TIMEOUT must be greater than zero");
        }
        if (execMaxTimeoutSeconds < 1) {
            throw new IllegalArgumentException("CODEX_EXEC_MAX_TIMEOUT must be greater than zero");
        }
        if (execDefaultTimeoutSeconds > execMaxTimeoutSeconds) {
            throw new IllegalArgumentException("CODEX_EXEC_DEFAULT_TIMEOUT must not exceed CODEX_EXEC_MAX_TIMEOUT");
        }
        if (maxRequestBytes < 1) {
            throw new IllegalArgumentException("CODEX_MAX_REQUEST_BYTES must be greater than zero");
        }
    }

    private static boolean isLocalHost(String host) {
        return "127.0.0.1".equals(host)
                || "localhost".equalsIgnoreCase(host)
                || "::1".equals(host);
    }

    private static String stringValue(Map<String, String> env, String name, String defaultValue) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }

    private static Optional<String> optionalStringValue(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value.trim());
    }

    private static int intValue(Map<String, String> env, String name, int defaultValue) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
    }

    private static boolean booleanValue(Map<String, String> env, String name, boolean defaultValue) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        String normalized = value.trim().toLowerCase();
        return switch (normalized) {
            case "true", "1", "yes", "y", "on" -> true;
            case "false", "0", "no", "n", "off" -> false;
            default -> throw new IllegalArgumentException(name + " must be a boolean value");
        };
    }
}
