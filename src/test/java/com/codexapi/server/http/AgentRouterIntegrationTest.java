package com.codexapi.server.http;

import com.codexapi.server.agent.AgentConnector;
import com.codexapi.server.agent.AgentConnectorService;
import com.codexapi.server.agent.AgentConnectorStatus;
import com.codexapi.server.agent.AgentPairingCodeResponse;
import com.codexapi.server.agent.AgentState;
import com.codexapi.server.agent.AgentStateStore;
import com.codexapi.server.config.AgentConfig;
import com.codexapi.server.config.Config;
import com.codexapi.server.process.ProcessRunner;
import com.codexapi.server.session.SessionService;
import com.codexapi.server.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AgentRouterIntegrationTest {
    @TempDir
    Path tempDir;

    private HttpServer apiServer;
    private HttpServer bridgeServer;
    private HttpClient client;
    private URI apiBaseUri;
    private final List<RecordedRequest> bridgeRequests = new ArrayList<>();
    private final Map<String, StubResponse> bridgeResponses = new LinkedHashMap<>();

    @BeforeEach
    void startBridge() throws Exception {
        bridgeServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        bridgeServer.createContext("/", this::handleBridge);
        bridgeServer.start();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void stopServers() {
        if (apiServer != null) {
            apiServer.stop(0);
        }
        bridgeServer.stop(0);
    }

    @Test
    void agentStatusEndpointIsTokenProtected() throws Exception {
        Config config = config(tempDir.resolve("agent.json"));
        startApi(config, new FakeConnector());

        HttpResponse<String> response = send(request("GET", "/api/agent/status").build());

        assertEquals(401, response.statusCode());
        assertTrue(response.body().contains("\"code\" : \"UNAUTHORIZED\""));
    }

    @Test
    void agentStatusResponseNeverContainsSecret() throws Exception {
        Config config = config(tempDir.resolve("agent.json"));
        startApi(config, new FakeConnector());

        HttpResponse<String> response = send(authorized(request("GET", "/api/agent/status")).build());
        JsonNode body = JsonUtil.fromJson(response.body(), JsonNode.class);

        assertEquals(200, response.statusCode());
        assertEquals(true, body.get("ok").asBoolean());
        assertEquals(true, body.get("enabled").asBoolean());
        assertEquals(true, body.get("active").asBoolean());
        assertEquals(true, body.get("connected").asBoolean());
        assertEquals("ag_status", body.get("agentId").asText());
        assertEquals("codex", body.get("agentType").asText());
        assertEquals("Dev Laptop", body.get("displayName").asText());
        assertEquals(bridgeBaseUrl(), body.get("bridgeBaseUrl").asText());
        assertEquals("ws://" + bridgeHostPort() + "/agent/ws", body.get("relayWebSocketUrl").asText());
        assertEquals("2026-05-03T00:10:00Z", body.get("pairingCodeExpiresAt").asText());
        assertEquals(true, body.get("workingDirectoryConfigured").asBoolean());
        assertFalse(response.body().contains("super-secret"));
        assertFalse(response.body().contains("agentSecret"));
    }

    @Test
    void pairingCodeEndpointReturnsCodeAndSendsBridgeAuth() throws Exception {
        bridgeResponses.put("/agent/pairing-codes", pairingResponse());
        Path stateFile = tempDir.resolve("agent.json");
        Config config = config(stateFile);
        new AgentStateStore(stateFile.toString()).save(state());
        AgentConnectorService connector = new AgentConnectorService(
                config,
                new ProcessRunner(),
                new SessionService(config)
        );
        startApi(config, connector);

        HttpResponse<String> response = send(authorized(request("POST", "/api/agent/pairing-code")).build());
        JsonNode body = JsonUtil.fromJson(response.body(), JsonNode.class);
        RecordedRequest bridgeRequest = onlyBridgeRequest();

        assertEquals(200, response.statusCode());
        assertEquals(true, body.get("ok").asBoolean());
        assertEquals("K7Q4-MD92-XP", body.get("pairingCode").asText());
        assertEquals("2026-05-03T00:10:00Z", body.get("expiresAt").asText());
        assertEquals("https://connect.example.test", body.get("connectUrl").asText());
        assertEquals("POST", bridgeRequest.method());
        assertEquals("/agent/pairing-codes", bridgeRequest.path());
        assertEquals("ag_status", bridgeRequest.header("X-Agent-Id"));
        assertEquals("Bearer super-secret", bridgeRequest.header("Authorization"));
    }

    @Test
    void pairingCodeEndpointFailsClearlyWithoutState() throws Exception {
        Config config = config(tempDir.resolve("missing-agent.json"));
        AgentConnectorService connector = new AgentConnectorService(
                config,
                new ProcessRunner(),
                new SessionService(config)
        );
        startApi(config, connector);

        HttpResponse<String> response = send(authorized(request("POST", "/api/agent/pairing-code")).build());

        assertEquals(409, response.statusCode());
        assertTrue(response.body().contains("\"code\" : \"AGENT_STATE_NOT_FOUND\""));
        assertEquals(0, bridgeRequests.size());
    }

    @Test
    void agentRoutesReturnMethodNotAllowedForUnsupportedMethods() throws Exception {
        Config config = config(tempDir.resolve("agent.json"));
        startApi(config, new FakeConnector());

        HttpResponse<String> statusResponse = send(authorized(request("PUT", "/api/agent/status")).build());
        HttpResponse<String> pairingResponse = send(authorized(request("GET", "/api/agent/pairing-code")).build());

        assertEquals(405, statusResponse.statusCode());
        assertTrue(statusResponse.body().contains("\"allow\" : \"GET\""));
        assertEquals(405, pairingResponse.statusCode());
        assertTrue(pairingResponse.body().contains("\"allow\" : \"POST\""));
    }

    private void startApi(Config config, AgentConnector connector) throws IOException {
        ProcessRunner processRunner = new ProcessRunner();
        SessionService sessionService = new SessionService(config);
        apiServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        apiServer.createContext("/", new Router(config, processRunner, sessionService, connector));
        apiServer.start();
        apiBaseUri = URI.create("http://127.0.0.1:" + apiServer.getAddress().getPort());
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String method, String path) {
        return HttpRequest.newBuilder(apiBaseUri.resolve(path)).method(method, HttpRequest.BodyPublishers.noBody());
    }

    private HttpRequest.Builder authorized(HttpRequest.Builder builder) {
        return builder.header("Authorization", "Bearer dev-token");
    }

    private Config config(Path stateFile) {
        return new Config(
                "127.0.0.1",
                8765,
                Optional.of("dev-token"),
                "codex",
                tempDir.resolve("sessions.json").toString(),
                600,
                1800,
                false,
                1_000_000,
                AgentConfig.fromEnvironment(Map.of(
                        "CODEX_AGENT_ENABLED", "true",
                        "CODEX_AGENT_BRIDGE_BASE_URL", bridgeBaseUrl(),
                        "CODEX_AGENT_STATE_FILE", stateFile.toString(),
                        "CODEX_AGENT_DISPLAY_NAME", "Dev Laptop",
                        "CODEX_AGENT_WORKING_DIRECTORY", tempDir.toString()
                ), 1800)
        );
    }

    private AgentState state() {
        return new AgentState(
                "ag_status",
                "super-secret",
                "codex",
                bridgeBaseUrl(),
                "ws://" + bridgeHostPort() + "/agent/ws",
                "Dev Laptop",
                "2026-05-03T00:00:00Z",
                "2026-05-03T00:05:00Z"
        );
    }

    private StubResponse pairingResponse() {
        return new StubResponse(200, """
                {
                  "ok": true,
                  "agentId": "ag_status",
                  "pairingCode": "K7Q4-MD92-XP",
                  "expiresAt": "2026-05-03T00:10:00Z",
                  "connectUrl": "https://connect.example.test"
                }
                """);
    }

    private RecordedRequest onlyBridgeRequest() {
        assertEquals(1, bridgeRequests.size());
        return bridgeRequests.get(0);
    }

    private String bridgeBaseUrl() {
        return "http://" + bridgeHostPort();
    }

    private String bridgeHostPort() {
        return "127.0.0.1:" + bridgeServer.getAddress().getPort();
    }

    private void handleBridge(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        bridgeRequests.add(new RecordedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI(),
                exchange.getRequestHeaders(),
                body
        ));
        StubResponse response = bridgeResponses.getOrDefault(
                exchange.getRequestURI().getPath(),
                new StubResponse(404, "{\"ok\":false}")
        );
        byte[] responseBytes = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(response.statusCode(), responseBytes.length);
        exchange.getResponseBody().write(responseBytes);
        exchange.close();
    }

    private final class FakeConnector implements AgentConnector {
        @Override
        public AgentConnectorStatus status() {
            return AgentConnectorStatus.ready(state(), "ready")
                    .withPairingCode(new AgentPairingCodeResponse(
                            true,
                            "ag_status",
                            "K7Q4-MD92-XP",
                            "2026-05-03T00:10:00Z",
                            "https://connect.example.test"
                    ))
                    .withConnected("2026-05-03T00:05:00Z")
                    .withSeen("2026-05-03T00:06:00Z");
        }

        @Override
        public AgentPairingCodeResponse requestPairingCode() {
            return new AgentPairingCodeResponse(
                    true,
                    "ag_status",
                    "K7Q4-MD92-XP",
                    "2026-05-03T00:10:00Z",
                    "https://connect.example.test"
            );
        }
    }

    private record StubResponse(int statusCode, String body) {
    }

    private record RecordedRequest(
            String method,
            URI uri,
            Headers headers,
            String body
    ) {
        String path() {
            return uri.getPath();
        }

        String header(String name) {
            return headers.getFirst(name);
        }
    }
}
