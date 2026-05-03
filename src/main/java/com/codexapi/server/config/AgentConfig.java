package com.codexapi.server.config;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

public record AgentConfig(
        boolean enabled,
        Optional<String> bridgeBaseUrl,
        String stateFile,
        String displayName,
        String clientVersion,
        String workingDirectory,
        boolean autoBootstrap,
        boolean autoPairOnFirstBootstrap,
        boolean pairOnStart,
        int heartbeatIntervalSeconds,
        int reconnectInitialSeconds,
        int reconnectMaxSeconds,
        int jobMaxTimeoutSeconds
) {
    private static final boolean DEFAULT_ENABLED = false;
    private static final String DEFAULT_STATE_FILE = "./data/agent.json";
    private static final String DEFAULT_DISPLAY_NAME = "Codex Local Agent";
    private static final String DEFAULT_CLIENT_VERSION = "codex-api-server/0.1.0-SNAPSHOT";
    private static final String DEFAULT_WORKING_DIRECTORY = ".";
    private static final boolean DEFAULT_AUTO_BOOTSTRAP = true;
    private static final boolean DEFAULT_AUTO_PAIR_ON_FIRST_BOOTSTRAP = true;
    private static final boolean DEFAULT_PAIR_ON_START = false;
    private static final int DEFAULT_HEARTBEAT_INTERVAL_SECONDS = 30;
    private static final int DEFAULT_RECONNECT_INITIAL_SECONDS = 2;
    private static final int DEFAULT_RECONNECT_MAX_SECONDS = 60;

    public static AgentConfig fromEnvironment(Map<String, String> env) {
        return fromEnvironment(env, intValue(env, "CODEX_EXEC_MAX_TIMEOUT", 1800));
    }

    public static AgentConfig fromEnvironment(Map<String, String> env, int execMaxTimeoutSeconds) {
        AgentConfig config = new AgentConfig(
                booleanValue(env, "CODEX_AGENT_ENABLED", DEFAULT_ENABLED),
                optionalNormalizedUrl(env, "CODEX_AGENT_BRIDGE_BASE_URL"),
                stringValue(env, "CODEX_AGENT_STATE_FILE", DEFAULT_STATE_FILE),
                stringValue(env, "CODEX_AGENT_DISPLAY_NAME", DEFAULT_DISPLAY_NAME),
                stringValue(env, "CODEX_AGENT_CLIENT_VERSION", DEFAULT_CLIENT_VERSION),
                normalizedAbsolutePath(stringValue(env, "CODEX_AGENT_WORKING_DIRECTORY", DEFAULT_WORKING_DIRECTORY)),
                booleanValue(env, "CODEX_AGENT_AUTO_BOOTSTRAP", DEFAULT_AUTO_BOOTSTRAP),
                booleanValue(env, "CODEX_AGENT_AUTO_PAIR_ON_FIRST_BOOTSTRAP", DEFAULT_AUTO_PAIR_ON_FIRST_BOOTSTRAP),
                booleanValue(env, "CODEX_AGENT_PAIR_ON_START", DEFAULT_PAIR_ON_START),
                intValue(env, "CODEX_AGENT_HEARTBEAT_INTERVAL_SECONDS", DEFAULT_HEARTBEAT_INTERVAL_SECONDS),
                intValue(env, "CODEX_AGENT_RECONNECT_INITIAL_SECONDS", DEFAULT_RECONNECT_INITIAL_SECONDS),
                intValue(env, "CODEX_AGENT_RECONNECT_MAX_SECONDS", DEFAULT_RECONNECT_MAX_SECONDS),
                intValue(env, "CODEX_AGENT_JOB_MAX_TIMEOUT_SECONDS", execMaxTimeoutSeconds)
        );

        config.validate();
        return config;
    }

    public boolean active() {
        return enabled && bridgeBaseUrl.isPresent();
    }

    public Optional<String> inactiveReason() {
        if (!enabled) {
            return Optional.of("Connector mode is disabled.");
        }
        if (bridgeBaseUrl.isEmpty()) {
            return Optional.of("CODEX_AGENT_BRIDGE_BASE_URL is not configured.");
        }
        return Optional.empty();
    }

    private void validate() {
        if (stateFile.isBlank()) {
            throw new IllegalArgumentException("CODEX_AGENT_STATE_FILE must not be blank");
        }
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("CODEX_AGENT_DISPLAY_NAME must not be blank");
        }
        if (clientVersion.isBlank()) {
            throw new IllegalArgumentException("CODEX_AGENT_CLIENT_VERSION must not be blank");
        }
        if (workingDirectory.isBlank()) {
            throw new IllegalArgumentException("CODEX_AGENT_WORKING_DIRECTORY must not be blank");
        }
        if (heartbeatIntervalSeconds < 1) {
            throw new IllegalArgumentException("CODEX_AGENT_HEARTBEAT_INTERVAL_SECONDS must be greater than zero");
        }
        if (reconnectInitialSeconds < 1) {
            throw new IllegalArgumentException("CODEX_AGENT_RECONNECT_INITIAL_SECONDS must be greater than zero");
        }
        if (reconnectMaxSeconds < 1) {
            throw new IllegalArgumentException("CODEX_AGENT_RECONNECT_MAX_SECONDS must be greater than zero");
        }
        if (reconnectInitialSeconds > reconnectMaxSeconds) {
            throw new IllegalArgumentException(
                    "CODEX_AGENT_RECONNECT_INITIAL_SECONDS must not exceed CODEX_AGENT_RECONNECT_MAX_SECONDS"
            );
        }
        if (jobMaxTimeoutSeconds < 1) {
            throw new IllegalArgumentException("CODEX_AGENT_JOB_MAX_TIMEOUT_SECONDS must be greater than zero");
        }
        if (active() && !Files.isDirectory(Path.of(workingDirectory))) {
            throw new IllegalArgumentException(
                    "CODEX_AGENT_WORKING_DIRECTORY must exist and be a directory when connector mode is active"
            );
        }
    }

    private static String normalizedAbsolutePath(String value) {
        return Path.of(value).toAbsolutePath().normalize().toString();
    }

    private static String stringValue(Map<String, String> env, String name, String defaultValue) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }

    private static Optional<String> optionalNormalizedUrl(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }

        String trimmed = value.trim();
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(name + " must be an http(s) URL");
        }
        String scheme = uri.getScheme();
        if ((!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) || uri.getHost() == null) {
            throw new IllegalArgumentException(name + " must be an http(s) URL");
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return Optional.of(trimmed);
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
