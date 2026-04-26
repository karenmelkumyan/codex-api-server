package com.codexapi.server;

import com.codexapi.server.config.Config;
import com.codexapi.server.http.Router;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws IOException {
        Config config = Config.fromEnvironment();
        InetSocketAddress address = new InetSocketAddress(config.host(), config.port());
        HttpServer server = HttpServer.create(address, 0);

        server.createContext("/", new Router(config));
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.printf("codex-api-server listening on http://%s:%d%n", config.host(), config.port());
    }
}
