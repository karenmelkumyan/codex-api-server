package com.codexapi.server.agent;

import com.codexapi.server.config.AgentConfig;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class BridgeAgentClientTest {
    @TempDir
    Path tempDir;

    private HttpServer server;
    private final List<RecordedRequest> requests = new ArrayList<>();
    private final Map<String, StubResponse> responses = new LinkedHashMap<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void bootstrapSendsExpectedJsonAndParsesResponse() {
        responses.put("/agent/bootstrap", new StubResponse(200, """
                {
                  "ok": true,
                  "agentId": "ag_test",
                  "agentSecret": "bootstrap-secret",
                  "agentType": "codex",
                  "displayName": "Dev Laptop",
                  "clientVersion": "client/1",
                  "status": "NEW",
                  "createdAt": "2026-05-03T00:00:00Z",
                  "relayEnabled": true,
                  "connectUrl": "https://connect.example.test"
                }
                """));

        AgentBootstrapResponse response = client().bootstrap(
                "codex",
                "Dev Laptop",
                "client/1",
                Map.of("supportsReadonly", true)
        );

        RecordedRequest request = onlyRequest();
        JsonNode body = JsonUtil.fromJson(request.body(), JsonNode.class);

        assertEquals("POST", request.method());
        assertEquals("/agent/bootstrap", request.path());
        assertNull(request.header("Authorization"));
        assertNull(request.header(BridgeAgentClient.AGENT_ID_HEADER));
        assertEquals("codex", body.get("agentType").asText());
        assertEquals("Dev Laptop", body.get("displayName").asText());
        assertEquals("client/1", body.get("clientVersion").asText());
        assertEquals(true, body.get("capabilities").get("supportsReadonly").asBoolean());
        assertEquals("ag_test", response.agentId());
        assertEquals("bootstrap-secret", response.agentSecret());
        assertEquals("ws://" + hostPort() + "/agent/ws", response.relayWebSocketUrl());
        assertEquals("https://connect.example.test", response.connectUrl());
    }

    @Test
    void bootstrapUsesRelayWebSocketUrlWhenBridgeReturnsOne() {
        responses.put("/agent/bootstrap", new StubResponse(200, """
                {
                  "ok": true,
                  "agentId": "ag_test",
                  "agentSecret": "bootstrap-secret",
                  "agentType": "codex",
                  "displayName": "Dev Laptop",
                  "clientVersion": "client/1",
                  "status": "NEW",
                  "createdAt": "2026-05-03T00:00:00Z",
                  "relayEnabled": true,
                  "connectUrl": "https://connect.example.test",
                  "relayWebSocketUrl": "wss://bridge.example.test/agent/ws"
                }
                """));

        AgentBootstrapResponse response = client().bootstrap("codex", "Dev Laptop", "client/1", Map.of());

        assertEquals("wss://bridge.example.test/agent/ws", response.relayWebSocketUrl());
    }

    @Test
    void pairingCodeRequestSendsAuthAndParsesResponse() {
        responses.put("/agent/pairing-codes", new StubResponse(200, """
                {
                  "ok": true,
                  "agentId": "ag_test",
                  "pairingCode": "K7Q4-MD92-XP",
                  "expiresAt": "2026-05-03T00:10:00Z",
                  "connectUrl": "https://connect.example.test"
                }
                """));

        AgentPairingCodeResponse response = client().createPairingCode(state(), "Dev Laptop");

        RecordedRequest request = onlyRequest();
        JsonNode body = JsonUtil.fromJson(request.body(), JsonNode.class);

        assertEquals("POST", request.method());
        assertEquals("/agent/pairing-codes", request.path());
        assertEquals("ag_test", request.header(BridgeAgentClient.AGENT_ID_HEADER));
        assertEquals("Bearer super-secret", request.header("Authorization"));
        assertEquals("Dev Laptop", body.get("displayName").asText());
        assertEquals("K7Q4-MD92-XP", response.pairingCode());
        assertEquals("2026-05-03T00:10:00Z", response.expiresAt());
        assertEquals("https://connect.example.test", response.connectUrl());
    }

    @Test
    void statusRequestSendsAuth() {
        responses.put("/agent/status", new StubResponse(200, """
                {
                  "ok": true,
                  "agent": {
                    "agentId": "ag_test",
                    "status": "ONLINE"
                  },
                  "relayEnabled": true,
                  "bootstrapEnabled": true,
                  "connectUrl": "https://connect.example.test",
                  "relayWebSocketUrl": "wss://bridge.example.test/agent/ws"
                }
                """));

        AgentStatusResponse response = client().status(state());

        RecordedRequest request = onlyRequest();

        assertEquals("GET", request.method());
        assertEquals("/agent/status", request.path());
        assertEquals("ag_test", request.header(BridgeAgentClient.AGENT_ID_HEADER));
        assertEquals("Bearer super-secret", request.header("Authorization"));
        assertEquals("ONLINE", response.agent().get("status").asText());
        assertEquals(true, response.relayEnabled());
        assertEquals(true, response.bootstrapEnabled());
        assertEquals("https://connect.example.test", response.connectUrl());
        assertEquals("wss://bridge.example.test/agent/ws", response.relayWebSocketUrl());
    }

    @Test
    void non2xxErrorDoesNotContainSecretOrAuthHeaders() {
        responses.put("/agent/status", new StubResponse(500, """
                bridge saw Authorization: Bearer super-secret and X-Agent-Id: ag_test
                """));

        BridgeAgentClientException exception = assertThrows(
                BridgeAgentClientException.class,
                () -> client().status(state())
        );

        String message = exception.getMessage();
        assertFalse(message.contains("super-secret"));
        assertFalse(message.toLowerCase().contains("authorization"));
        assertFalse(message.toLowerCase().contains("x-agent-id"));
    }

    @Test
    void derivesWebSocketUrlFromBridgeBaseUrl() {
        assertEquals(
                "ws://bridge.example.test/agent/ws",
                BridgeAgentClient.deriveRelayWebSocketUrl("http://bridge.example.test")
        );
        assertEquals(
                "wss://bridge.example.test/base/agent/ws",
                BridgeAgentClient.deriveRelayWebSocketUrl("https://bridge.example.test/base/")
        );
    }

    private BridgeAgentClient client() {
        AgentConfig config = AgentConfig.fromEnvironment(Map.of(
                "CODEX_AGENT_ENABLED", "true",
                "CODEX_AGENT_BRIDGE_BASE_URL", baseUrl(),
                "CODEX_AGENT_WORKING_DIRECTORY", tempDir.toString()
        ));
        return new BridgeAgentClient(config);
    }

    private AgentState state() {
        return new AgentState(
                "ag_test",
                "super-secret",
                "codex",
                baseUrl(),
                "ws://" + hostPort() + "/agent/ws",
                "Dev Laptop",
                "2026-05-03T00:00:00Z",
                null
        );
    }

    private RecordedRequest onlyRequest() {
        assertEquals(1, requests.size());
        return requests.get(0);
    }

    private String baseUrl() {
        return "http://" + hostPort();
    }

    private String hostPort() {
        return "127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new RecordedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI(),
                exchange.getRequestHeaders(),
                body
        ));

        StubResponse response = responses.getOrDefault(
                exchange.getRequestURI().getPath(),
                new StubResponse(404, "{\"ok\":false}")
        );
        byte[] responseBytes = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(response.statusCode(), responseBytes.length);
        exchange.getResponseBody().write(responseBytes);
        exchange.close();
    }

    private record StubResponse(int statusCode, String body) {
    }

    private record RecordedRequest(
            String method,
            URI uri,
            Map<String, List<String>> headers,
            String body
    ) {
        RecordedRequest(String method, URI uri, Headers headers, String body) {
            this(method, uri, copy(headers), body);
        }

        String path() {
            return uri.getPath();
        }

        String header(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                    return entry.getValue().get(0);
                }
            }
            return null;
        }

        private static Map<String, List<String>> copy(Headers headers) {
            Map<String, List<String>> copied = new LinkedHashMap<>();
            headers.forEach((name, values) -> copied.put(name, List.copyOf(values)));
            return copied;
        }
    }
}
