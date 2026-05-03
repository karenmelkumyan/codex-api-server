package com.codexapi.server.agent;

import com.codexapi.server.config.AgentConfig;
import com.codexapi.server.config.Config;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AgentConnectorServiceTest {
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-05-03T00:00:00Z"), ZoneOffset.UTC);

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
    void disabledConnectorDoesNotCallBridgeOrState() {
        AgentConnectorService service = new AgentConnectorService(
                config(Map.of("CODEX_AGENT_ENABLED", "false")),
                () -> {
                    throw new AssertionError("state store should not be loaded");
                },
                () -> {
                    throw new AssertionError("bridge client should not be created");
                },
                () -> {
                    throw new AssertionError("capabilities should not be read");
                },
                printStream(output()),
                FIXED_CLOCK
        );

        AgentConnectorStatus status = service.start();

        assertFalse(status.enabled());
        assertFalse(status.active());
        assertEquals(0, requests.size());
    }

    @Test
    void enabledWithoutBridgeUrlReportsInactiveWithoutState() {
        ByteArrayOutputStream output = output();
        AgentConnectorService service = new AgentConnectorService(
                config(Map.of("CODEX_AGENT_ENABLED", "true")),
                () -> {
                    throw new AssertionError("state store should not be loaded");
                },
                () -> {
                    throw new AssertionError("bridge client should not be created");
                },
                () -> {
                    throw new AssertionError("capabilities should not be read");
                },
                printStream(output),
                FIXED_CLOCK
        );

        AgentConnectorStatus status = service.start();

        assertFalse(status.active());
        assertEquals("CODEX_AGENT_BRIDGE_BASE_URL is not configured.", status.message());
        assertTrue(outputText(output).contains("codex connector inactive"));
        assertEquals(0, requests.size());
    }

    @Test
    void missingStateWithAutoBootstrapSavesReturnedState() {
        responses.put("/agent/bootstrap", bootstrapResponse("ag_boot", "bootstrap-secret"));
        Path stateFile = tempDir.resolve("agent.json");

        AgentConnectorStatus status = service(
                config(Map.of(
                        "CODEX_AGENT_ENABLED", "true",
                        "CODEX_AGENT_BRIDGE_BASE_URL", baseUrl(),
                        "CODEX_AGENT_STATE_FILE", stateFile.toString(),
                        "CODEX_AGENT_AUTO_PAIR_ON_FIRST_BOOTSTRAP", "false"
                )),
                output()
        ).start();

        AgentState saved = new AgentStateStore(stateFile.toString()).get().orElseThrow();

        assertTrue(status.active());
        assertEquals("ag_boot", saved.agentId());
        assertEquals("bootstrap-secret", saved.agentSecret());
        assertEquals(baseUrl(), saved.bridgeBaseUrl());
        assertEquals("ws://" + hostPort() + "/agent/ws", saved.relayWebSocketUrl());
        assertEquals("Dev Laptop", saved.displayName());
        assertEquals("2026-05-03T00:00:00Z", saved.createdAt());
        assertEquals(List.of("/agent/bootstrap"), requestPaths());
    }

    @Test
    void autoBootstrapFalseWithMissingStateStaysInactive() {
        Path stateFile = tempDir.resolve("agent.json");

        AgentConnectorStatus status = service(
                config(Map.of(
                        "CODEX_AGENT_ENABLED", "true",
                        "CODEX_AGENT_BRIDGE_BASE_URL", baseUrl(),
                        "CODEX_AGENT_STATE_FILE", stateFile.toString(),
                        "CODEX_AGENT_AUTO_BOOTSTRAP", "false"
                )),
                output()
        ).start();

        assertFalse(status.active());
        assertTrue(status.message().contains("CODEX_AGENT_AUTO_BOOTSTRAP=false"));
        assertFalse(Files.exists(stateFile));
        assertEquals(0, requests.size());
    }

    @Test
    void firstBootstrapPairingPrintsUrlCodeAndExpiryWithoutSecret() {
        responses.put("/agent/bootstrap", bootstrapResponse("ag_boot", "bootstrap-secret"));
        responses.put("/agent/pairing-codes", pairingResponse());
        ByteArrayOutputStream output = output();

        AgentConnectorStatus status = service(
                config(Map.of(
                        "CODEX_AGENT_ENABLED", "true",
                        "CODEX_AGENT_BRIDGE_BASE_URL", baseUrl(),
                        "CODEX_AGENT_STATE_FILE", tempDir.resolve("agent.json").toString(),
                        "CODEX_AGENT_AUTO_PAIR_ON_FIRST_BOOTSTRAP", "true",
                        "CODEX_AGENT_PAIR_ON_START", "true"
                )),
                output
        ).start();

        String printed = outputText(output);

        assertTrue(status.active());
        assertEquals(List.of("/agent/bootstrap", "/agent/pairing-codes"), requestPaths());
        assertTrue(printed.contains("https://connect.example.test"));
        assertTrue(printed.contains("K7Q4-MD92-XP"));
        assertTrue(printed.contains("2026-05-03T00:10:00Z"));
        assertFalse(printed.contains("bootstrap-secret"));
    }

    @Test
    void existingStateWithPairOnStartRequestsPairingCodeWithoutBootstrap() {
        Path stateFile = tempDir.resolve("agent.json");
        new AgentStateStore(stateFile.toString()).save(state(baseUrl()));
        responses.put("/agent/pairing-codes", pairingResponse());
        ByteArrayOutputStream output = output();

        AgentConnectorStatus status = service(
                config(Map.of(
                        "CODEX_AGENT_ENABLED", "true",
                        "CODEX_AGENT_BRIDGE_BASE_URL", baseUrl(),
                        "CODEX_AGENT_STATE_FILE", stateFile.toString(),
                        "CODEX_AGENT_PAIR_ON_START", "true"
                )),
                output
        ).start();

        String printed = outputText(output);

        assertTrue(status.active());
        assertEquals(List.of("/agent/pairing-codes"), requestPaths());
        assertTrue(printed.contains("K7Q4-MD92-XP"));
        assertFalse(printed.contains("super-secret"));
    }

    @Test
    void invalidStateIsNotOverwritten() throws Exception {
        Path stateFile = tempDir.resolve("agent.json");
        String invalidJson = "{ not valid json";
        Files.writeString(stateFile, invalidJson);
        ByteArrayOutputStream output = output();

        AgentConnectorStatus status = service(
                config(Map.of(
                        "CODEX_AGENT_ENABLED", "true",
                        "CODEX_AGENT_BRIDGE_BASE_URL", baseUrl(),
                        "CODEX_AGENT_STATE_FILE", stateFile.toString()
                )),
                output
        ).start();

        assertFalse(status.active());
        assertEquals(invalidJson, Files.readString(stateFile));
        assertTrue(outputText(output).contains("Delete CODEX_AGENT_STATE_FILE to reset connector identity."));
        assertEquals(0, requests.size());
    }

    @Test
    void bridgeMismatchIsClearAndDoesNotOverwrite() {
        Path stateFile = tempDir.resolve("agent.json");
        new AgentStateStore(stateFile.toString()).save(state("https://old-bridge.example.test"));
        ByteArrayOutputStream output = output();

        AgentConnectorStatus status = service(
                config(Map.of(
                        "CODEX_AGENT_ENABLED", "true",
                        "CODEX_AGENT_BRIDGE_BASE_URL", baseUrl(),
                        "CODEX_AGENT_STATE_FILE", stateFile.toString()
                )),
                output
        ).start();

        AgentState saved = new AgentStateStore(stateFile.toString()).get().orElseThrow();

        assertFalse(status.active());
        assertTrue(status.message().contains("pair with a different bridge"));
        assertTrue(outputText(output).contains("pair with a different bridge"));
        assertEquals("https://old-bridge.example.test", saved.bridgeBaseUrl());
        assertEquals(0, requests.size());
    }

    @Test
    void bootstrapMissingAgentIdFailsSafely() {
        responses.put("/agent/bootstrap", new StubResponse(200, """
                {
                  "ok": true,
                  "agentSecret": "bootstrap-secret",
                  "agentType": "codex",
                  "displayName": "Dev Laptop",
                  "createdAt": "2026-05-03T00:00:00Z"
                }
                """));
        Path stateFile = tempDir.resolve("agent.json");

        AgentConnectorStatus status = service(
                config(Map.of(
                        "CODEX_AGENT_ENABLED", "true",
                        "CODEX_AGENT_BRIDGE_BASE_URL", baseUrl(),
                        "CODEX_AGENT_STATE_FILE", stateFile.toString()
                )),
                output()
        ).start();

        assertFalse(status.active());
        assertEquals("Bridge response missing agentId.", status.message());
        assertFalse(Files.exists(stateFile));
    }

    @Test
    void bootstrapMissingAgentSecretFailsSafelyWithoutLeakingResponseSecret() {
        responses.put("/agent/bootstrap", new StubResponse(200, """
                {
                  "ok": true,
                  "agentId": "ag_boot",
                  "agentType": "codex",
                  "displayName": "Dev Laptop",
                  "createdAt": "2026-05-03T00:00:00Z"
                }
                """));
        Path stateFile = tempDir.resolve("agent.json");
        ByteArrayOutputStream output = output();

        AgentConnectorStatus status = service(
                config(Map.of(
                        "CODEX_AGENT_ENABLED", "true",
                        "CODEX_AGENT_BRIDGE_BASE_URL", baseUrl(),
                        "CODEX_AGENT_STATE_FILE", stateFile.toString()
                )),
                output
        ).start();

        assertFalse(status.active());
        assertEquals("Bridge bootstrap response missing required secret.", status.message());
        assertFalse(Files.exists(stateFile));
        assertFalse(outputText(output).contains("agentSecret"));
    }

    @Test
    void unexpectedStartupFailureReturnsInactiveStatus() {
        AgentConnectorService service = new AgentConnectorService(
                config(Map.of(
                        "CODEX_AGENT_ENABLED", "true",
                        "CODEX_AGENT_BRIDGE_BASE_URL", baseUrl()
                )),
                () -> {
                    throw new IllegalArgumentException("boom");
                },
                () -> {
                    throw new AssertionError("bridge client should not be created");
                },
                Map::of,
                printStream(output()),
                FIXED_CLOCK
        );

        AgentConnectorStatus status = service.start();

        assertFalse(status.active());
        assertEquals("boom", status.message());
    }

    private AgentConnectorService service(Config config, ByteArrayOutputStream output) {
        return new AgentConnectorService(
                config,
                () -> new AgentStateStore(config.agent().stateFile()),
                () -> new BridgeAgentClient(config.agent()),
                () -> Map.of(
                        "codexCliAvailable", true,
                        "supportsReadonly", true,
                        "supportsVerify", true,
                        "supportsChange", true,
                        "workingDirectoryConfigured", true
                ),
                printStream(output),
                FIXED_CLOCK
        );
    }

    private Config config(Map<String, String> overrides) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("CODEX_AGENT_STATE_FILE", tempDir.resolve("unused-agent.json").toString());
        env.put("CODEX_AGENT_WORKING_DIRECTORY", tempDir.toString());
        env.putAll(overrides);

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
                AgentConfig.fromEnvironment(env, 1800)
        );
    }

    private AgentState state(String bridgeBaseUrl) {
        return new AgentState(
                "ag_existing",
                "super-secret",
                "codex",
                bridgeBaseUrl,
                "ws://" + hostPort() + "/agent/ws",
                "Dev Laptop",
                "2026-05-03T00:00:00Z",
                null
        );
    }

    private StubResponse bootstrapResponse(String agentId, String agentSecret) {
        return new StubResponse(200, """
                {
                  "ok": true,
                  "agentId": "%s",
                  "agentSecret": "%s",
                  "agentType": "codex",
                  "displayName": "Dev Laptop",
                  "clientVersion": "client/1",
                  "status": "NEW",
                  "createdAt": "2026-05-03T00:00:00Z",
                  "relayEnabled": true,
                  "connectUrl": "https://connect.example.test"
                }
                """.formatted(agentId, agentSecret));
    }

    private StubResponse pairingResponse() {
        return new StubResponse(200, """
                {
                  "ok": true,
                  "agentId": "ag_existing",
                  "pairingCode": "K7Q4-MD92-XP",
                  "expiresAt": "2026-05-03T00:10:00Z",
                  "connectUrl": "https://connect.example.test"
                }
                """);
    }

    private List<String> requestPaths() {
        return requests.stream().map(RecordedRequest::path).toList();
    }

    private ByteArrayOutputStream output() {
        return new ByteArrayOutputStream();
    }

    private PrintStream printStream(ByteArrayOutputStream output) {
        return new PrintStream(output, true, StandardCharsets.UTF_8);
    }

    private String outputText(ByteArrayOutputStream output) {
        return output.toString(StandardCharsets.UTF_8);
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

        private static Map<String, List<String>> copy(Headers headers) {
            Map<String, List<String>> copied = new LinkedHashMap<>();
            headers.forEach((name, values) -> copied.put(name, List.copyOf(values)));
            return copied;
        }
    }
}
