package com.codexapi.server.http;

import com.codexapi.server.agent.AgentConnector;
import com.codexapi.server.agent.AgentConnectorException;
import com.codexapi.server.agent.AgentConnectorService;
import com.codexapi.server.agent.AgentConnectorStatus;
import com.codexapi.server.agent.AgentPairingCodeResponse;
import com.codexapi.server.config.Config;
import com.codexapi.server.codex.CodexExecRequest;
import com.codexapi.server.codex.CodexService;
import com.codexapi.server.file.FileService;
import com.codexapi.server.git.GitService;
import com.codexapi.server.history.HistoryService;
import com.codexapi.server.process.ProcessResult;
import com.codexapi.server.process.ProcessRunner;
import com.codexapi.server.session.CreateSessionRequest;
import com.codexapi.server.session.Session;
import com.codexapi.server.session.SessionNotFoundException;
import com.codexapi.server.session.SessionService;
import com.codexapi.server.session.SessionStorageException;
import com.codexapi.server.session.SessionValidationException;
import com.codexapi.server.util.JsonUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class Router implements HttpHandler {
    private final Config config;
    private final ProcessRunner processRunner;
    private final SessionService sessionService;
    private final FileService fileService;
    private final GitService gitService;
    private final HistoryService historyService;
    private final CodexService codexService;
    private final AgentConnector agentConnector;

    public Router(Config config) {
        this(config, new ProcessRunner(), new SessionService(config));
    }

    public Router(Config config, ProcessRunner processRunner, SessionService sessionService) {
        this(config, processRunner, sessionService, null);
    }

    public Router(
            Config config,
            ProcessRunner processRunner,
            SessionService sessionService,
            AgentConnector agentConnector
    ) {
        this.config = config;
        this.processRunner = processRunner;
        this.sessionService = sessionService;
        this.fileService = new FileService();
        this.gitService = new GitService(processRunner, Duration.ofSeconds(config.execDefaultTimeoutSeconds()));
        this.historyService = new HistoryService(sessionService.store());
        this.codexService = new CodexService(config, processRunner, sessionService, historyService);
        this.agentConnector = agentConnector == null
                ? new AgentConnectorService(config, processRunner, sessionService)
                : agentConnector;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();

            if ("GET".equals(method) && "/health".equals(path)) {
                JsonResponseWriter.writeJson(exchange, 200, Map.of(
                        "status", "ok",
                        "service", "codex-api-server"
                ));
                return;
            }
            if ("/health".equals(path)) {
                throw methodNotAllowed("GET");
            }

            if (isApiPath(path)) {
                if (!isAuthorized(exchange)) {
                    return;
                }

                if ("GET".equals(method) && "/api/codex/status".equals(path)) {
                    handleCodexStatus(exchange);
                    return;
                }
                if ("/api/codex/status".equals(path)) {
                    throw methodNotAllowed("GET");
                }

                if ("GET".equals(method) && "/api/agent/status".equals(path)) {
                    handleAgentStatus(exchange);
                    return;
                }
                if ("/api/agent/status".equals(path)) {
                    throw methodNotAllowed("GET");
                }

                if ("POST".equals(method) && "/api/agent/pairing-code".equals(path)) {
                    handleAgentPairingCode(exchange);
                    return;
                }
                if ("/api/agent/pairing-code".equals(path)) {
                    throw methodNotAllowed("POST");
                }

                if (handleSessionRoute(exchange, method, path)) {
                    return;
                }
            }

            JsonResponseWriter.writeError(
                    exchange,
                    404,
                    "NOT_FOUND",
                    "No route is implemented for this path yet."
            );
        } catch (SessionValidationException exception) {
            JsonResponseWriter.writeError(
                    exchange,
                    400,
                    "VALIDATION_ERROR",
                    exception.getMessage(),
                    exception.details()
            );
        } catch (SessionNotFoundException exception) {
            JsonResponseWriter.writeError(
                    exchange,
                    404,
                    "SESSION_NOT_FOUND",
                    "Session was not found.",
                    Map.of("sessionId", exception.sessionId())
            );
        } catch (SessionStorageException exception) {
            JsonResponseWriter.writeError(
                    exchange,
                    500,
                    "SESSION_STORAGE_ERROR",
                    "Unable to read or write session data."
            );
        } catch (ApiException exception) {
            JsonResponseWriter.writeError(
                    exchange,
                    exception.statusCode(),
                    exception.code(),
                    exception.getMessage(),
                    exception.details()
            );
        } catch (AgentConnectorException exception) {
            JsonResponseWriter.writeError(
                    exchange,
                    exception.statusCode(),
                    exception.code(),
                    exception.getMessage()
            );
        } catch (Exception exception) {
            JsonResponseWriter.writeError(
                    exchange,
                    500,
                    "INTERNAL_ERROR",
                    "An unexpected server error occurred."
            );
        } finally {
            exchange.close();
        }
    }

    private boolean isAuthorized(HttpExchange exchange) throws IOException {
        Optional<String> configuredToken = config.apiToken();
        if (configuredToken.isEmpty()) {
            JsonResponseWriter.writeError(
                    exchange,
                    503,
                    "AUTH_TOKEN_NOT_CONFIGURED",
                    "Protected API endpoints require CODEX_API_TOKEN to be configured."
            );
            return false;
        }

        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        Optional<String> bearerToken = readBearerToken(authorization);
        if (bearerToken.isEmpty() || !tokensEqual(configuredToken.get(), bearerToken.get())) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            JsonResponseWriter.writeError(
                    exchange,
                    401,
                    "UNAUTHORIZED",
                    "A valid bearer token is required for this endpoint."
            );
            return false;
        }

        return true;
    }

    private boolean handleSessionRoute(HttpExchange exchange, String method, String path) throws IOException {
        if ("/api/sessions".equals(path)) {
            if ("POST".equals(method)) {
                handleCreateSession(exchange);
                return true;
            }
            if ("GET".equals(method)) {
                JsonResponseWriter.writeJson(exchange, 200, Map.of("sessions", sessionService.list()));
                return true;
            }
            throw methodNotAllowed("GET, POST");
        }

        String prefix = "/api/sessions/";
        if (!path.startsWith(prefix)) {
            return false;
        }

        String sessionPath = path.substring(prefix.length());
        if (sessionPath.isBlank()) {
            return false;
        }

        int slashIndex = sessionPath.indexOf('/');
        String sessionId = slashIndex < 0 ? sessionPath : sessionPath.substring(0, slashIndex);
        String subPath = slashIndex < 0 ? "" : sessionPath.substring(slashIndex);

        if (sessionId.isBlank()) {
            return false;
        }

        if (subPath.isEmpty() && "GET".equals(method)) {
            JsonResponseWriter.writeJson(exchange, 200, sessionService.get(sessionId));
            return true;
        }

        if (subPath.isEmpty() && "DELETE".equals(method)) {
            sessionService.delete(sessionId);
            JsonResponseWriter.writeJson(exchange, 200, Map.of(
                    "deleted", true,
                    "sessionId", sessionId
            ));
            return true;
        }
        if (subPath.isEmpty()) {
            throw methodNotAllowed("GET, DELETE");
        }

        Session session = sessionService.get(sessionId);
        Map<String, String> query = queryParameters(exchange);

        if ("/codex/exec".equals(subPath) && "POST".equals(method)) {
            handleCodexExec(exchange, session);
            return true;
        }
        if ("/codex/exec".equals(subPath)) {
            throw methodNotAllowed("POST");
        }

        if (!"GET".equals(method)) {
            if (isKnownSessionGetSubPath(subPath)) {
                throw methodNotAllowed("GET");
            }
            return false;
        }

        if ("/history".equals(subPath)) {
            JsonResponseWriter.writeJson(exchange, 200, Map.of(
                    "sessionId", session.sessionId(),
                    "history", historyService.list(session.sessionId())
            ));
            return true;
        }

        if ("/tree".equals(subPath)) {
            JsonResponseWriter.writeJson(exchange, 200, fileService.tree(
                    session,
                    query.getOrDefault("path", "."),
                    positiveInt(query.get("maxDepth"), 4, "maxDepth"),
                    positiveInt(query.get("limit"), 500, "limit")
            ));
            return true;
        }

        if ("/files/content".equals(subPath)) {
            JsonResponseWriter.writeJson(exchange, 200, fileService.content(
                    session,
                    query.get("path"),
                    positiveInt(query.get("maxBytes"), 200_000, "maxBytes"),
                    query.getOrDefault("encoding", StandardCharsets.UTF_8.name())
            ));
            return true;
        }

        if ("/git/status".equals(subPath)) {
            JsonResponseWriter.writeJson(exchange, 200, gitService.status(session));
            return true;
        }

        if ("/git/diff".equals(subPath)) {
            JsonResponseWriter.writeJson(exchange, 200, gitService.diff(
                    session,
                    booleanValue(query.get("staged"), false, "staged"),
                    positiveInt(query.get("maxBytes"), 300_000, "maxBytes")
            ));
            return true;
        }

        return false;
    }

    private void handleCreateSession(HttpExchange exchange) throws IOException {
        CreateSessionRequest request = readJsonBody(exchange, CreateSessionRequest.class);
        Session session = sessionService.create(request);
        JsonResponseWriter.writeJson(exchange, 201, session);
    }

    private void handleCodexExec(HttpExchange exchange, Session session) throws IOException {
        CodexExecRequest request = readJsonBody(exchange, CodexExecRequest.class);
        JsonResponseWriter.writeJson(exchange, 200, codexService.execute(session, request));
    }

    private void handleAgentStatus(HttpExchange exchange) throws IOException {
        JsonResponseWriter.writeJson(exchange, 200, agentStatusResponse(agentConnector.status()));
    }

    private void handleAgentPairingCode(HttpExchange exchange) throws IOException {
        AgentPairingCodeResponse pairingCode = agentConnector.requestPairingCode();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("pairingCode", pairingCode.pairingCode());
        response.put("expiresAt", pairingCode.expiresAt());
        response.put("connectUrl", pairingCode.connectUrl());
        JsonResponseWriter.writeJson(exchange, 200, response);
    }

    private Map<String, Object> agentStatusResponse(AgentConnectorStatus status) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("enabled", status.enabled());
        response.put("active", status.active());
        response.put("connected", status.connected());
        response.put("status", status.status());
        response.put("message", status.message());
        response.put("agentId", status.agentId());
        response.put("agentType", status.agentType());
        response.put("displayName", status.displayName());
        response.put("bridgeBaseUrl", status.bridgeBaseUrl());
        response.put("relayWebSocketUrl", status.relayWebSocketUrl());
        response.put("lastSeenAt", status.lastSeenAt());
        response.put("lastConnectedAt", status.lastConnectedAt());
        response.put("lastDisconnectedAt", status.lastDisconnectedAt());
        response.put("pairingCodeExpiresAt", status.pairingCodeExpiresAt());
        response.put("workingDirectoryConfigured", workingDirectoryConfigured());
        return response;
    }

    private boolean workingDirectoryConfigured() {
        String workingDirectory = config.agent().workingDirectory();
        return workingDirectory != null
                && !workingDirectory.isBlank()
                && Files.isDirectory(Path.of(workingDirectory));
    }

    private <T> T readJsonBody(HttpExchange exchange, Class<T> type) throws IOException {
        String json = readBody(exchange);
        if (json.isBlank()) {
            throw new ApiException(400, "INVALID_JSON", "Request body must be valid JSON.");
        }

        try {
            return JsonUtil.fromJson(json, type);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(400, "INVALID_JSON", "Request body must be valid JSON.");
        }
    }

    private String readBody(HttpExchange exchange) throws IOException {
        int maxBytes = config.maxRequestBytes();
        try (InputStream inputStream = exchange.getRequestBody()) {
            ByteArrayOutputStream body = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
            byte[] buffer = new byte[8192];
            int totalBytes = 0;
            int bytesRead;

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                totalBytes += bytesRead;
                if (totalBytes > maxBytes) {
                    throw new ApiException(
                            413,
                            "REQUEST_TOO_LARGE",
                            "Request body exceeds the configured maximum size.",
                            Map.of("maxRequestBytes", maxBytes)
                    );
                }
                body.write(buffer, 0, bytesRead);
            }

            return body.toString(StandardCharsets.UTF_8);
        }
    }

    private void handleCodexStatus(HttpExchange exchange) throws IOException {
        String cliPath = config.codexCliPath();
        ProcessResult result = processRunner.run(
                List.of(cliPath, "--version"),
                Duration.ofSeconds(config.execDefaultTimeoutSeconds())
        );

        JsonResponseWriter.writeJson(exchange, 200, codexStatusResponse(cliPath, result));
    }

    private static Map<String, Object> codexStatusResponse(String cliPath, ProcessResult result) {
        Map<String, Object> response = new LinkedHashMap<>();
        boolean available = result.started()
                && !result.timedOut()
                && result.exitCode() != null
                && result.exitCode() == 0;

        response.put("available", available);
        response.put("cliPath", cliPath);

        if (available) {
            response.put("version", result.stdout().trim());
            response.put("exitCode", result.exitCode());
            response.put("durationMs", result.durationMs());
            return response;
        }

        if (!result.started()) {
            response.put("error", "Codex CLI not found or not executable");
            response.put("durationMs", result.durationMs());
            return response;
        }

        if (result.timedOut()) {
            response.put("error", "Codex CLI status check timed out");
        } else {
            response.put("error", "Codex CLI exited with a non-zero status");
        }
        response.put("exitCode", result.exitCode());
        response.put("durationMs", result.durationMs());
        addIfNotBlank(response, "stdout", result.stdout());
        addIfNotBlank(response, "stderr", result.stderr());
        return response;
    }

    private static void addIfNotBlank(Map<String, Object> response, String key, String value) {
        if (value != null && !value.isBlank()) {
            response.put(key, value.trim());
        }
    }

    private static Map<String, String> queryParameters(HttpExchange exchange) {
        String rawQuery = exchange.getRequestURI().getRawQuery();
        if (rawQuery == null || rawQuery.isBlank()) {
            return Map.of();
        }

        Map<String, String> parameters = new LinkedHashMap<>();
        String[] pairs = rawQuery.split("&");
        for (String pair : pairs) {
            if (pair.isBlank()) {
                continue;
            }

            int equalsIndex = pair.indexOf('=');
            String rawName = equalsIndex < 0 ? pair : pair.substring(0, equalsIndex);
            String rawValue = equalsIndex < 0 ? "" : pair.substring(equalsIndex + 1);
            parameters.put(
                    URLDecoder.decode(rawName, StandardCharsets.UTF_8),
                    URLDecoder.decode(rawValue, StandardCharsets.UTF_8)
            );
        }
        return parameters;
    }

    private static int positiveInt(String value, int defaultValue, String field) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            int parsedValue = Integer.parseInt(value.trim());
            if (parsedValue < 1) {
                throw new NumberFormatException("not positive");
            }
            return parsedValue;
        } catch (NumberFormatException exception) {
            throw new ApiException(400, "VALIDATION_ERROR", field + " must be a positive integer", Map.of("field", field));
        }
    }

    private static boolean booleanValue(String value, boolean defaultValue, String field) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        return switch (value.trim().toLowerCase()) {
            case "true", "1", "yes", "y", "on" -> true;
            case "false", "0", "no", "n", "off" -> false;
            default -> throw new ApiException(
                    400,
                    "VALIDATION_ERROR",
                    field + " must be a boolean value",
                    Map.of("field", field)
            );
        };
    }

    private static boolean isKnownSessionGetSubPath(String subPath) {
        return "/history".equals(subPath)
                || "/tree".equals(subPath)
                || "/files/content".equals(subPath)
                || "/git/status".equals(subPath)
                || "/git/diff".equals(subPath);
    }

    private static ApiException methodNotAllowed(String allow) {
        return new ApiException(
                405,
                "METHOD_NOT_ALLOWED",
                "Method is not allowed for this endpoint.",
                Map.of("allow", allow)
        );
    }

    private static boolean isApiPath(String path) {
        return "/api".equals(path) || path.startsWith("/api/");
    }

    private static Optional<String> readBearerToken(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return Optional.empty();
        }

        String prefix = "Bearer ";
        if (!authorization.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return Optional.empty();
        }

        String token = authorization.substring(prefix.length()).trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }

    private static boolean tokensEqual(String configuredToken, String providedToken) {
        return MessageDigest.isEqual(
                configuredToken.getBytes(StandardCharsets.UTF_8),
                providedToken.getBytes(StandardCharsets.UTF_8)
        );
    }
}
