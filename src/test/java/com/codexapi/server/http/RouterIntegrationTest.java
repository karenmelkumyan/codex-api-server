package com.codexapi.server.http;

import com.codexapi.server.config.AgentConfig;
import com.codexapi.server.config.Config;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RouterIntegrationTest {
    @TempDir
    Path tempDir;

    private HttpServer server;
    private HttpClient client;
    private URI baseUri;

    @BeforeEach
    void startServer() throws Exception {
        Config config = new Config(
                "127.0.0.1",
                8765,
                Optional.of("dev-token"),
                "codex",
                tempDir.resolve("sessions.json").toString(),
                600,
                1800,
                false,
                64,
                AgentConfig.fromEnvironment(Map.of(
                        "CODEX_AGENT_STATE_FILE", tempDir.resolve("agent-state.json").toString(),
                        "CODEX_AGENT_WORKING_DIRECTORY", tempDir.toString()
                ))
        );
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", new Router(config));
        server.start();
        client = HttpClient.newHttpClient();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void healthIsPublicAndJson() throws Exception {
        HttpResponse<String> response = send(request("GET", "/health").build());

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/json; charset=utf-8"));
    }

    @Test
    void apiWithoutTokenReturnsUnauthorized() throws Exception {
        HttpResponse<String> response = send(request("GET", "/api/unknown").build());

        assertEquals(401, response.statusCode());
        assertTrue(response.body().contains("\"code\" : \"UNAUTHORIZED\""));
    }

    @Test
    void apiWithWrongTokenReturnsUnauthorized() throws Exception {
        HttpResponse<String> response = send(authorized(request("GET", "/api/unknown"), "wrong-token").build());

        assertEquals(401, response.statusCode());
    }

    @Test
    void apiWithValidTokenProceedsToRouteHandling() throws Exception {
        HttpResponse<String> response = send(authorized(request("GET", "/api/unknown"), "dev-token").build());

        assertEquals(404, response.statusCode());
        assertTrue(response.body().contains("\"code\" : \"NOT_FOUND\""));
    }

    @Test
    void unsupportedMethodOnKnownRouteReturnsMethodNotAllowed() throws Exception {
        HttpResponse<String> response = send(authorized(request("PUT", "/api/sessions"), "dev-token").build());

        assertEquals(405, response.statusCode());
        assertTrue(response.body().contains("\"code\" : \"METHOD_NOT_ALLOWED\""));
    }

    @Test
    void invalidJsonReturnsInvalidJson() throws Exception {
        HttpRequest request = authorized(request("POST", "/api/sessions"), "dev-token")
                .header("Content-Type", "application/json")
                .method("POST", HttpRequest.BodyPublishers.ofString("{"))
                .build();

        HttpResponse<String> response = send(request);

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("\"code\" : \"INVALID_JSON\""));
    }

    @Test
    void emptyJsonBodyReturnsInvalidJson() throws Exception {
        HttpRequest request = authorized(request("POST", "/api/sessions"), "dev-token")
                .header("Content-Type", "application/json")
                .method("POST", HttpRequest.BodyPublishers.ofString(""))
                .build();

        HttpResponse<String> response = send(request);

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("\"code\" : \"INVALID_JSON\""));
    }

    @Test
    void oversizedJsonBodyReturnsRequestTooLarge() throws Exception {
        String body = "{\"name\":\"" + "x".repeat(100) + "\"}";
        HttpRequest request = authorized(request("POST", "/api/sessions"), "dev-token")
                .header("Content-Type", "application/json")
                .method("POST", HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = send(request);

        assertEquals(413, response.statusCode());
        assertTrue(response.body().contains("\"code\" : \"REQUEST_TOO_LARGE\""));
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String method, String path) {
        return HttpRequest.newBuilder(baseUri.resolve(path)).method(method, HttpRequest.BodyPublishers.noBody());
    }

    private HttpRequest.Builder authorized(HttpRequest.Builder builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
    }
}
