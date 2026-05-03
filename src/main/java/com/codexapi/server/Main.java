package com.codexapi.server;

import com.codexapi.server.agent.AgentConnectorService;
import com.codexapi.server.config.Config;
import com.codexapi.server.http.Router;
import com.codexapi.server.process.ProcessRunner;
import com.codexapi.server.session.SessionService;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws IOException {
        Config config = Config.fromEnvironment();
        ProcessRunner processRunner = new ProcessRunner();
        SessionService sessionService = new SessionService(config);
        AgentConnectorService connectorService = new AgentConnectorService(config, processRunner, sessionService);
        InetSocketAddress address = new InetSocketAddress(config.host(), config.port());
        HttpServer server = HttpServer.create(address, 0);

        server.createContext("/", new Router(config, processRunner, sessionService, connectorService));
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.printf("codex-api-server listening on http://%s:%d%n", config.host(), config.port());
        try {
            connectorService.start();
        } catch (RuntimeException exception) {
            System.err.println("codex connector startup failed unexpectedly; local HTTP API is still running.");
        }
    }
}
