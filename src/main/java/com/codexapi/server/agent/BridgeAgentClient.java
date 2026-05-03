package com.codexapi.server.agent;

import com.codexapi.server.config.AgentConfig;
import com.codexapi.server.util.JsonUtil;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

public final class BridgeAgentClient {
    static final String AGENT_ID_HEADER = "X-Agent-Id";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final int ERROR_BODY_LIMIT = 500;

    private final AgentConfig config;
    private final HttpClient httpClient;

    public BridgeAgentClient(AgentConfig config) {
        this(config, HttpClient.newHttpClient());
    }

    BridgeAgentClient(AgentConfig config, HttpClient httpClient) {
        this.config = config;
        this.httpClient = httpClient;
    }

    public AgentBootstrapResponse bootstrap(
            String agentType,
            String displayName,
            String clientVersion,
            Map<String, Object> capabilities
    ) {
        AgentBootstrapRequest request = new AgentBootstrapRequest(agentType, displayName, clientVersion, capabilities);
        HttpRequest httpRequest = baseRequest("/agent/bootstrap")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonUtil.toJson(request), StandardCharsets.UTF_8))
                .build();

        AgentBootstrapResponse response = send(httpRequest, "POST", "/agent/bootstrap", null, AgentBootstrapResponse.class);
        if (response.relayWebSocketUrl() == null || response.relayWebSocketUrl().isBlank()) {
            return response.withRelayWebSocketUrl(deriveRelayWebSocketUrl(bridgeBaseUrl()));
        }
        return response;
    }

    public AgentPairingCodeResponse createPairingCode(AgentState state, String displayName) {
        AgentPairingCodeRequest request = new AgentPairingCodeRequest(displayName);
        HttpRequest httpRequest = authenticatedRequest("/agent/pairing-codes", state)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonUtil.toJson(request), StandardCharsets.UTF_8))
                .build();

        return send(
                httpRequest,
                "POST",
                "/agent/pairing-codes",
                state.agentSecret(),
                AgentPairingCodeResponse.class
        );
    }

    public AgentStatusResponse status(AgentState state) {
        HttpRequest httpRequest = authenticatedRequest("/agent/status", state).GET().build();
        return send(httpRequest, "GET", "/agent/status", state.agentSecret(), AgentStatusResponse.class);
    }

    static String deriveRelayWebSocketUrl(String bridgeBaseUrl) {
        URI relayHttpUri = URI.create(trimTrailingSlash(bridgeBaseUrl) + "/agent/ws");
        String httpScheme = relayHttpUri.getScheme();
        if (httpScheme == null) {
            throw new BridgeAgentClientException("Bridge base URL must use http or https.");
        }
        String scheme = switch (httpScheme.toLowerCase()) {
            case "http" -> "ws";
            case "https" -> "wss";
            default -> throw new BridgeAgentClientException("Bridge base URL must use http or https.");
        };

        try {
            return new URI(
                    scheme,
                    relayHttpUri.getUserInfo(),
                    relayHttpUri.getHost(),
                    relayHttpUri.getPort(),
                    relayHttpUri.getPath(),
                    relayHttpUri.getQuery(),
                    relayHttpUri.getFragment()
            ).toString();
        } catch (URISyntaxException exception) {
            throw new BridgeAgentClientException("Unable to derive relay WebSocket URL from bridge base URL.", exception);
        }
    }

    private HttpRequest.Builder authenticatedRequest(String path, AgentState state) {
        return baseRequest(path)
                .header(AGENT_ID_HEADER, state.agentId())
                .header("Authorization", "Bearer " + state.agentSecret());
    }

    private HttpRequest.Builder baseRequest(String path) {
        return HttpRequest.newBuilder(endpoint(path))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json");
    }

    private URI endpoint(String path) {
        return URI.create(bridgeBaseUrl() + path);
    }

    private String bridgeBaseUrl() {
        return config.bridgeBaseUrl()
                .map(BridgeAgentClient::trimTrailingSlash)
                .orElseThrow(() -> new BridgeAgentClientException("CODEX_AGENT_BRIDGE_BASE_URL is not configured."));
    }

    private <T> T send(
            HttpRequest request,
            String method,
            String path,
            String agentSecret,
            Class<T> responseType
    ) {
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BridgeAgentClientException("Bridge request " + method + " " + path + " was interrupted.", exception);
        } catch (IOException exception) {
            throw new BridgeAgentClientException("Bridge request " + method + " " + path + " failed.", exception);
        }

        int statusCode = response.statusCode();
        if (statusCode < 200 || statusCode >= 300) {
            String message = "Bridge request " + method + " " + path + " failed with HTTP " + statusCode;
            String safeBody = safeErrorBody(response.body(), agentSecret);
            if (!safeBody.isBlank()) {
                message += ": " + safeBody;
            }
            throw new BridgeAgentClientException(message);
        }

        try {
            return JsonUtil.fromJson(response.body(), responseType);
        } catch (IllegalArgumentException exception) {
            throw new BridgeAgentClientException(
                    "Bridge request " + method + " " + path + " returned invalid JSON.",
                    exception
            );
        }
    }

    private static String safeErrorBody(String body, String agentSecret) {
        if (body == null || body.isBlank()) {
            return "";
        }

        String sanitized = body
                .replace("\r", " ")
                .replace("\n", " ")
                .replaceAll("(?i)authorization", "auth")
                .replaceAll("(?i)x-agent-id", "agent-id");
        if (agentSecret != null && !agentSecret.isBlank()) {
            sanitized = sanitized
                    .replace("Bearer " + agentSecret, "[redacted]")
                    .replace(agentSecret, "[redacted]");
        }

        if (sanitized.length() > ERROR_BODY_LIMIT) {
            return sanitized.substring(0, ERROR_BODY_LIMIT) + "...[truncated]";
        }
        return sanitized;
    }

    private static String trimTrailingSlash(String value) {
        String trimmed = value;
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private record AgentBootstrapRequest(
            String agentType,
            String displayName,
            String clientVersion,
            Map<String, Object> capabilities
    ) {
    }

    private record AgentPairingCodeRequest(String displayName) {
    }
}
