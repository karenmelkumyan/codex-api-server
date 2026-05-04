package com.codexapi.server.agent;

import java.util.Collection;
import java.util.regex.Pattern;

final class AgentTranscriptSanitizer {
    static final int MAX_CONTENT_CHARS = 3_500;

    private static final String REDACTED = "[redacted]";
    private static final String TOOL_URL_REDACTED = "[redacted tool url]";
    private static final String COMMAND_REDACTED = "[redacted command]";
    private static final String TRUNCATED_MARKER = "\n[chunk truncated]";

    private static final Pattern BEARER_PATTERN = Pattern.compile(
            "(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+"
    );
    private static final Pattern KEY_VALUE_SECRET_PATTERN = Pattern.compile(
            "(?i)\\b(secret|token|password|authorization|api[_-]?key|apikey|agentSecret|bridgeKey|EME_SESSION_TOKEN|CODEX_API_TOKEN|BRIDGE_ADMIN_TOKEN)\\b\\s*[:=]\\s*([^\\s,;]+)"
    );
    private static final Pattern JSON_SECRET_PATTERN = Pattern.compile(
            "(?i)(\"(?:secret|token|password|authorization|api[_-]?key|apikey|agentSecret|bridgeKey|EME_SESSION_TOKEN|CODEX_API_TOKEN|BRIDGE_ADMIN_TOKEN)\"\\s*:\\s*\")([^\"]*)(\")"
    );
    private static final Pattern TOOL_URL_PATTERN = Pattern.compile(
            "(?i)\\b(?:https?|wss?)://\\S+/(?:tools|agent/ws)(?:/|\\?|\\S*)?\\S*"
    );
    private static final Pattern CODEX_EXEC_COMMAND_PATTERN = Pattern.compile(
            "(?im)^.*\\bcodex\\s+exec\\b.*$"
    );
    private static final Pattern UNIX_HOME_PATH_PATTERN = Pattern.compile(
            "(/(?:Users|home)/)[^/\\s]+/(?:Documents/GitHub/)?"
    );
    private static final Pattern PRIVATE_VAR_PATH_PATTERN = Pattern.compile(
            "/private/var/folders/[^\\s\"'`<>]+"
    );
    private static final Pattern WINDOWS_HOME_PATH_PATTERN = Pattern.compile(
            "(?i)[A-Z]:\\\\Users\\\\[^\\\\\\s]+\\\\"
    );

    SanitizedTranscriptContent sanitize(String content, boolean alreadyTruncated, Collection<String> exactSecrets) {
        if (content == null) {
            return new SanitizedTranscriptContent(alreadyTruncated ? "[chunk truncated]" : "", false, alreadyTruncated);
        }

        String safe = normalizeLineEndings(content);
        boolean redacted = false;

        ExactReplacement exactReplacement = redactExactSecrets(safe, exactSecrets);
        safe = exactReplacement.value();
        redacted = redacted || exactReplacement.changed();

        Replacement replacement = replace(CODEX_EXEC_COMMAND_PATTERN, safe, COMMAND_REDACTED);
        safe = replacement.value();
        redacted = redacted || replacement.changed();

        replacement = replace(TOOL_URL_PATTERN, safe, TOOL_URL_REDACTED);
        safe = replacement.value();
        redacted = redacted || replacement.changed();

        replacement = replace(BEARER_PATTERN, safe, "Bearer " + REDACTED);
        safe = replacement.value();
        redacted = redacted || replacement.changed();

        replacement = replace(KEY_VALUE_SECRET_PATTERN, safe, "$1=" + REDACTED);
        safe = replacement.value();
        redacted = redacted || replacement.changed();

        replacement = replace(JSON_SECRET_PATTERN, safe, "$1" + REDACTED + "$3");
        safe = replacement.value();
        redacted = redacted || replacement.changed();

        replacement = replace(UNIX_HOME_PATH_PATTERN, safe, "[local path]/");
        safe = replacement.value();
        redacted = redacted || replacement.changed();

        replacement = replace(PRIVATE_VAR_PATH_PATTERN, safe, "[local path]");
        safe = replacement.value();
        redacted = redacted || replacement.changed();

        replacement = replace(WINDOWS_HOME_PATH_PATTERN, safe, "[local path]\\\\");
        safe = replacement.value();
        redacted = redacted || replacement.changed();

        boolean truncated = alreadyTruncated || safe.length() > MAX_CONTENT_CHARS;
        if (safe.length() > MAX_CONTENT_CHARS) {
            safe = truncateWithMarker(safe);
        } else if (alreadyTruncated && !safe.endsWith("[chunk truncated]")) {
            safe = appendMarker(safe);
        }

        return new SanitizedTranscriptContent(safe, redacted, truncated);
    }

    private ExactReplacement redactExactSecrets(String value, Collection<String> exactSecrets) {
        String safe = value;
        boolean changed = false;
        if (exactSecrets != null) {
            for (String secret : exactSecrets) {
                if (secret == null || secret.isBlank()) {
                    continue;
                }
                String updated = safe.replace(secret, REDACTED);
                changed = changed || !updated.equals(safe);
                safe = updated;
            }
        }
        return new ExactReplacement(safe, changed);
    }

    private String normalizeLineEndings(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private Replacement replace(Pattern pattern, String value, String replacement) {
        String updated = pattern.matcher(value).replaceAll(replacement);
        return new Replacement(updated, !updated.equals(value));
    }

    private String truncateWithMarker(String value) {
        int prefixLength = Math.max(0, MAX_CONTENT_CHARS - TRUNCATED_MARKER.length());
        return value.substring(0, Math.min(prefixLength, value.length())) + TRUNCATED_MARKER;
    }

    private String appendMarker(String value) {
        if (value.length() + TRUNCATED_MARKER.length() <= MAX_CONTENT_CHARS) {
            return value + TRUNCATED_MARKER;
        }
        return truncateWithMarker(value);
    }

    record SanitizedTranscriptContent(String content, boolean redacted, boolean truncated) {
    }

    private record Replacement(String value, boolean changed) {
    }

    private record ExactReplacement(String value, boolean changed) {
    }
}
