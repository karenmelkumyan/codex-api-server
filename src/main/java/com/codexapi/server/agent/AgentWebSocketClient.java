package com.codexapi.server.agent;

import com.codexapi.server.config.AgentConfig;
import com.codexapi.server.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

public final class AgentWebSocketClient {
    private static final String AGENT_ID_HEADER = "X-Agent-Id";

    private final AgentConfig config;
    private final AgentStateStore stateStore;
    private final Supplier<Map<String, Object>> capabilitiesSupplier;
    private final StatusListener statusListener;
    private final AgentJobHandler jobHandler;
    private final Dialer dialer;
    private final TaskScheduler scheduler;
    private final Clock clock;
    private final IntSupplier jitterMillisSupplier;
    private final AtomicBoolean stopped = new AtomicBoolean(true);
    private final AtomicReference<AgentState> stateRef = new AtomicReference<>();

    private final Object lock = new Object();
    private Cancellable heartbeatTask;
    private boolean reconnectPending;
    private int nextReconnectDelaySeconds;

    public AgentWebSocketClient(
            AgentConfig config,
            AgentStateStore stateStore,
            Supplier<Map<String, Object>> capabilitiesSupplier,
            StatusListener statusListener,
            AgentJobHandler jobHandler
    ) {
        this(
                config,
                stateStore,
                capabilitiesSupplier,
                statusListener,
                jobHandler,
                new JavaNetDialer(HttpClient.newHttpClient()),
                new ExecutorTaskScheduler(),
                Clock.systemUTC(),
                () -> ThreadLocalRandom.current().nextInt(1_000)
        );
    }

    AgentWebSocketClient(
            AgentConfig config,
            AgentStateStore stateStore,
            Supplier<Map<String, Object>> capabilitiesSupplier,
            StatusListener statusListener,
            AgentJobHandler jobHandler,
            Dialer dialer,
            TaskScheduler scheduler,
            Clock clock,
            IntSupplier jitterMillisSupplier
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.capabilitiesSupplier = Objects.requireNonNull(capabilitiesSupplier, "capabilitiesSupplier");
        this.statusListener = Objects.requireNonNull(statusListener, "statusListener");
        this.jobHandler = Objects.requireNonNull(jobHandler, "jobHandler");
        this.dialer = Objects.requireNonNull(dialer, "dialer");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.jitterMillisSupplier = Objects.requireNonNull(jitterMillisSupplier, "jitterMillisSupplier");
        this.nextReconnectDelaySeconds = config.reconnectInitialSeconds();
    }

    public void start(AgentState state) {
        stateRef.set(Objects.requireNonNull(state, "state"));
        stopped.set(false);
        scheduleConnect(Duration.ZERO);
    }

    public void stop() {
        stopped.set(true);
        synchronized (lock) {
            cancelHeartbeat();
            reconnectPending = false;
        }
    }

    private void scheduleConnect(Duration delay) {
        scheduler.schedule(this::connect, delay);
    }

    private void connect() {
        if (stopped.get()) {
            return;
        }

        AgentState state = stateRef.get();
        if (state == null) {
            return;
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(AGENT_ID_HEADER, state.agentId());
        headers.put("Authorization", "Bearer " + state.agentSecret());

        try {
            dialer.connect(URI.create(state.relayWebSocketUrl()), headers, new AgentListener())
                    .whenComplete((webSocket, exception) -> {
                        if (exception != null) {
                            handleDisconnected("WebSocket connect failed.");
                        }
                    });
        } catch (RuntimeException exception) {
            handleDisconnected("WebSocket connect failed.");
        }
    }

    private void handleOpen(WebSocket webSocket) {
        synchronized (lock) {
            reconnectPending = false;
            nextReconnectDelaySeconds = config.reconnectInitialSeconds();
            cancelHeartbeat();
            heartbeatTask = scheduler.scheduleAtFixedRate(
                    () -> sendPing(webSocket),
                    Duration.ofSeconds(config.heartbeatIntervalSeconds()),
                    Duration.ofSeconds(config.heartbeatIntervalSeconds())
            );
        }

        String timestamp = Instant.now(clock).toString();
        persistLastConnectedAt(timestamp);
        statusListener.connected(timestamp);
        sendHello(webSocket);
    }

    private void persistLastConnectedAt(String timestamp) {
        AgentState current = stateRef.get();
        if (current == null) {
            return;
        }
        try {
            AgentState updated = current.withLastConnectedAt(timestamp);
            stateStore.save(updated);
            stateRef.set(updated);
        } catch (RuntimeException exception) {
            // Connection diagnostics should not tear down an otherwise valid socket.
        }
    }

    private void sendHello(WebSocket webSocket) {
        AgentState state = stateRef.get();
        if (state == null) {
            return;
        }

        Map<String, Object> hello = new LinkedHashMap<>();
        hello.put("type", "agent.hello");
        hello.put("agentId", state.agentId());
        hello.put("displayName", state.displayName());
        hello.put("clientVersion", config.clientVersion());
        hello.put("capabilities", capabilitiesSupplier.get());
        sendJson(webSocket, hello);
    }

    private void sendPing(WebSocket webSocket) {
        Map<String, Object> ping = new LinkedHashMap<>();
        ping.put("type", "ping");
        ping.put("time", Instant.now(clock).toString());
        sendJson(webSocket, ping);
    }

    private void handleMessage(WebSocket webSocket, String payload) {
        JsonNode root;
        try {
            root = JsonUtil.fromJson(payload, JsonNode.class);
        } catch (IllegalArgumentException exception) {
            return;
        }
        if (root == null || !root.isObject()) {
            return;
        }

        String type = textOrNull(root.get("type"));
        if (type == null) {
            return;
        }

        switch (type) {
            case "ping" -> handlePing(webSocket, root);
            case "pong", "agent.hello_ack" -> markSeen();
            case "job.request" -> handleJobRequest(webSocket, root);
            default -> {
            }
        }
    }

    private void handlePing(WebSocket webSocket, JsonNode root) {
        markSeen();
        Map<String, Object> pong = new LinkedHashMap<>();
        pong.put("type", "pong");
        String time = textOrNull(root.get("time"));
        pong.put("time", time == null ? Instant.now(clock).toString() : time);
        sendJson(webSocket, pong);
    }

    private void handleJobRequest(WebSocket webSocket, JsonNode root) {
        markSeen();
        String jobId = textOrNull(root.get("jobId"));
        if (jobId == null) {
            return;
        }

        AgentRelayJobRequest request = new AgentRelayJobRequest(
                jobId,
                textOrNull(root.get("tool")),
                textOrNull(root.get("message")),
                integerOrNull(root.get("timeoutSeconds"))
        );

        Map<String, Object> accepted = new LinkedHashMap<>();
        accepted.put("type", "job.accepted");
        accepted.put("jobId", jobId);
        sendJson(webSocket, accepted);

        jobHandler.handleAsync(request).whenComplete((result, exception) -> {
            if (exception != null) {
                sendJobResult(webSocket, AgentJobExecutionResult.failed(
                        jobId,
                        "Codex execution failed.",
                        null,
                        false,
                        AgentJobHandler.CODEX_EXEC_FAILED
                ));
                return;
            }
            sendJobResult(webSocket, result);
        });
    }

    private void sendJobResult(WebSocket webSocket, AgentJobExecutionResult executionResult) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "job.result");
        payload.put("jobId", executionResult.jobId());
        payload.put("ok", executionResult.ok());
        payload.put("status", executionResult.status());
        payload.put("message", executionResult.message());
        payload.put("exitCode", executionResult.exitCode());
        payload.put("timedOut", executionResult.timedOut());
        payload.put("stderrPresent", executionResult.stderrPresent());
        payload.put("error", executionResult.error());
        sendJson(webSocket, payload);
    }

    private void markSeen() {
        statusListener.seen(Instant.now(clock).toString());
    }

    private void handleDisconnected(String reason) {
        if (stopped.get()) {
            return;
        }

        Duration reconnectDelay;
        synchronized (lock) {
            cancelHeartbeat();
            if (reconnectPending) {
                return;
            }
            reconnectPending = true;
            reconnectDelay = nextReconnectDelay();
        }

        statusListener.disconnected(reason, Instant.now(clock).toString());
        scheduler.schedule(() -> {
            synchronized (lock) {
                reconnectPending = false;
            }
            connect();
        }, reconnectDelay);
    }

    private Duration nextReconnectDelay() {
        int delaySeconds = nextReconnectDelaySeconds;
        nextReconnectDelaySeconds = Math.min(delaySeconds * 2, config.reconnectMaxSeconds());
        int jitterMillis = Math.max(0, jitterMillisSupplier.getAsInt());
        return Duration.ofSeconds(delaySeconds).plusMillis(jitterMillis);
    }

    private void cancelHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
            heartbeatTask = null;
        }
    }

    private void sendJson(WebSocket webSocket, Map<String, Object> payload) {
        webSocket.sendText(JsonUtil.toJson(payload), true);
    }

    private String textOrNull(JsonNode node) {
        if (node == null || node.isNull() || !node.isValueNode()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value;
    }

    private Integer integerOrNull(JsonNode node) {
        return node != null && node.canConvertToInt() ? node.asInt() : null;
    }

    interface StatusListener {
        void connected(String timestamp);

        void disconnected(String reason, String timestamp);

        void seen(String timestamp);
    }

    interface Dialer {
        CompletableFuture<WebSocket> connect(URI uri, Map<String, String> headers, WebSocket.Listener listener);
    }

    interface TaskScheduler {
        Cancellable schedule(Runnable task, Duration delay);

        Cancellable scheduleAtFixedRate(Runnable task, Duration initialDelay, Duration period);
    }

    interface Cancellable {
        void cancel();
    }

    private final class AgentListener implements WebSocket.Listener {
        private final StringBuilder textBuffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            handleOpen(webSocket);
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            try {
                textBuffer.append(data);
                if (last) {
                    String payload = textBuffer.toString();
                    textBuffer.setLength(0);
                    handleMessage(webSocket, payload);
                }
                return CompletableFuture.completedFuture(null);
            } finally {
                webSocket.request(1);
            }
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            handleDisconnected("WebSocket disconnected.");
            requestNext(webSocket);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            handleDisconnected("WebSocket error.");
            requestNext(webSocket);
        }

        private void requestNext(WebSocket webSocket) {
            if (webSocket != null) {
                webSocket.request(1);
            }
        }
    }

    private static final class JavaNetDialer implements Dialer {
        private final HttpClient httpClient;

        private JavaNetDialer(HttpClient httpClient) {
            this.httpClient = httpClient;
        }

        @Override
        public CompletableFuture<WebSocket> connect(
                URI uri,
                Map<String, String> headers,
                WebSocket.Listener listener
        ) {
            WebSocket.Builder builder = httpClient.newWebSocketBuilder();
            headers.forEach(builder::header);
            return builder.buildAsync(uri, listener);
        }
    }

    private static final class ExecutorTaskScheduler implements TaskScheduler {
        private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(command -> {
            Thread thread = new Thread(command, "codex-agent-websocket");
            thread.setDaemon(true);
            return thread;
        });

        @Override
        public Cancellable schedule(Runnable task, Duration delay) {
            var future = executor.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        }

        @Override
        public Cancellable scheduleAtFixedRate(Runnable task, Duration initialDelay, Duration period) {
            var future = executor.scheduleAtFixedRate(
                    task,
                    initialDelay.toMillis(),
                    period.toMillis(),
                    TimeUnit.MILLISECONDS
            );
            return () -> future.cancel(false);
        }
    }
}
