package com.codexapi.server.agent;

import com.codexapi.server.config.AgentConfig;
import com.codexapi.server.process.ProcessOutputListener;
import com.codexapi.server.process.ProcessOutputStream;
import com.codexapi.server.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AgentWebSocketClientTest {
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-05-03T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    @Test
    void webSocketRequestIncludesAuthHeadersAndSendsHelloWithoutSecret() {
        Fixture fixture = fixture();

        fixture.startAndOpen();

        JsonNode hello = json(fixture.webSocket().sentTexts().get(0));
        AgentState saved = new AgentStateStore(fixture.stateStore().stateFile().toString()).get().orElseThrow();

        assertEquals(URI.create("ws://bridge.example.test/agent/ws"), fixture.dialer().lastUri());
        assertEquals("ag_test", fixture.dialer().lastHeaders().get("X-Agent-Id"));
        assertEquals("Bearer super-secret", fixture.dialer().lastHeaders().get("Authorization"));
        assertEquals("agent.hello", hello.get("type").asText());
        assertEquals("ag_test", hello.get("agentId").asText());
        assertEquals("Dev Laptop", hello.get("displayName").asText());
        assertEquals("client/1", hello.get("clientVersion").asText());
        assertEquals(true, hello.get("capabilities").get("supportsReadonly").asBoolean());
        assertFalse(fixture.webSocket().sentTexts().get(0).contains("super-secret"));
        assertEquals("2026-05-03T00:00:00Z", saved.lastConnectedAt());
        assertEquals("2026-05-03T00:00:00Z", fixture.status().connectedAt());
        assertEquals(1, fixture.webSocket().requested());
    }

    @Test
    void heartbeatPingIsSent() {
        Fixture fixture = fixture();
        fixture.startAndOpen();

        fixture.scheduler().runNextPeriodic();

        JsonNode ping = json(fixture.webSocket().lastText());
        assertEquals("ping", ping.get("type").asText());
        assertEquals("2026-05-03T00:00:00Z", ping.get("time").asText());
    }

    @Test
    void incomingPingGetsPong() {
        Fixture fixture = fixture();
        fixture.startAndOpen();

        fixture.receive("""
                {"type":"ping","time":"bridge-time"}
                """);

        JsonNode pong = json(fixture.webSocket().lastText());
        assertEquals("pong", pong.get("type").asText());
        assertEquals("bridge-time", pong.get("time").asText());
        assertEquals("2026-05-03T00:00:00Z", fixture.status().seenAt());
    }

    @Test
    void helloAckAndPongUpdateSeenStatus() {
        Fixture fixture = fixture();
        fixture.startAndOpen();

        fixture.receive("""
                {"type":"agent.hello_ack","ok":true}
                """);
        fixture.receive("""
                {"type":"pong","time":"agent-time"}
                """);

        assertEquals("2026-05-03T00:00:00Z", fixture.status().seenAt());
    }

    @Test
    void incomingJobProgressMessageIsIgnoredSafely() {
        Fixture fixture = fixture();
        fixture.startAndOpen();
        int sentBefore = fixture.webSocket().sentTexts().size();

        fixture.receive("""
                {
                  "type": "job.progress",
                  "jobId": "job_1",
                  "eventType": "progress",
                  "message": "unexpected inbound progress"
                }
                """);

        assertEquals(sentBefore, fixture.webSocket().sentTexts().size());
    }

    @Test
    void closeTriggersReconnectBackoffAndCancelsHeartbeat() {
        Fixture fixture = fixture();
        fixture.startAndOpen();
        FakeScheduler.ScheduledTask heartbeat = fixture.scheduler().latestPeriodic();

        fixture.dialer().lastListener().onClose(fixture.webSocket(), 1000, "bye");

        assertTrue(heartbeat.cancelled());
        assertEquals("WebSocket disconnected.", fixture.status().disconnectedReason());
        assertEquals(Duration.ofSeconds(2), fixture.scheduler().latestOneShotDelay());

        fixture.scheduler().runNextOneShot();

        assertEquals(2, fixture.dialer().connectCount());
    }

    @Test
    void partialTextFramesAssembleBeforeParsing() {
        Fixture fixture = fixture();
        fixture.startAndOpen();
        int sentBefore = fixture.webSocket().sentTexts().size();

        fixture.dialer().lastListener().onText(fixture.webSocket(), "{\"type\":\"pi", false);
        assertEquals(sentBefore, fixture.webSocket().sentTexts().size());

        fixture.dialer().lastListener().onText(fixture.webSocket(), "ng\",\"time\":\"t\"}", true);

        JsonNode pong = json(fixture.webSocket().lastText());
        assertEquals("pong", pong.get("type").asText());
        assertEquals("t", pong.get("time").asText());
        assertEquals(3, fixture.webSocket().requested());
    }

    @Test
    void jobRequestSendsAcceptedBeforeHandlerResult() {
        Fixture fixture = fixture();
        fixture.startAndOpen();

        fixture.receive("""
                {
                  "type": "job.request",
                  "jobId": "job_1",
                  "tool": "codex_change",
                  "message": "make it so",
                  "timeoutSeconds": 30
                }
                """);

        List<String> sentTexts = fixture.webSocket().sentTexts();
        JsonNode accepted = json(sentTexts.get(sentTexts.size() - 3));
        JsonNode progress = json(sentTexts.get(sentTexts.size() - 2));
        JsonNode result = json(sentTexts.get(sentTexts.size() - 1));

        assertTrue(fixture.jobHandler().acceptedWasSentBeforeStart());
        assertEquals("job.accepted", accepted.get("type").asText());
        assertEquals("job_1", accepted.get("jobId").asText());
        assertEquals("job.progress", progress.get("type").asText());
        assertEquals("job_1", progress.get("jobId").asText());
        assertEquals("progress", progress.get("eventType").asText());
        assertEquals("Codex execution started.", progress.get("message").asText());
        assertTrue(progress.get("details").isObject());
        assertFalse(progress.toString().contains("super-secret"));
        assertEquals("job.result", result.get("type").asText());
        assertEquals("job_1", result.get("jobId").asText());
        assertEquals(true, result.get("ok").asBoolean());
        assertEquals("completed", result.get("status").asText());
        assertEquals(false, result.get("stderrPresent").asBoolean());
        assertTrue(result.get("error").isNull());
    }

    @Test
    void runningJobSendsHeartbeatProgressUntilResultCompletes() {
        Fixture fixture = pendingFixture();
        fixture.startAndOpen();

        fixture.receive("""
                {
                  "type": "job.request",
                  "jobId": "job_1",
                  "tool": "codex_change",
                  "message": "make it so",
                  "timeoutSeconds": 30
                }
                """);
        FakeScheduler.ScheduledTask progressHeartbeat = fixture.scheduler().latestPeriodic();

        fixture.scheduler().runNextPeriodic();

        JsonNode heartbeat = json(fixture.webSocket().lastText());
        assertEquals("job.progress", heartbeat.get("type").asText());
        assertEquals("heartbeat", heartbeat.get("eventType").asText());
        assertEquals("Codex is still running.", heartbeat.get("message").asText());
        assertTrue(heartbeat.get("details").isObject());
        assertFalse(heartbeat.toString().contains("super-secret"));

        fixture.jobHandler().complete(AgentJobExecutionResult.completed("job_1", "done", 0, false));

        JsonNode result = json(fixture.webSocket().lastText());
        assertTrue(progressHeartbeat.cancelled());
        assertEquals("job.result", result.get("type").asText());
        assertEquals("completed", result.get("status").asText());
    }

    @Test
    void jobResultSerializesErrorAsString() {
        Fixture fixture = fixture(AgentJobExecutionResult.failed(
                "job_2",
                "Unsupported tool.",
                null,
                false,
                "UNSUPPORTED_TOOL"
        ));
        fixture.startAndOpen();

        fixture.receive("""
                {
                  "type": "job.request",
                  "jobId": "job_2",
                  "tool": "codex_read_log",
                  "message": "show logs",
                  "timeoutSeconds": 30
                }
                """);

        JsonNode result = json(fixture.webSocket().lastText());

        assertEquals("job.result", result.get("type").asText());
        assertEquals("UNSUPPORTED_TOOL", result.get("error").asText());
        assertTrue(result.get("error").isTextual());
    }

    @Test
    void jobRequestSendsSanitizedTranscriptBeforeResult() {
        Fixture fixture = fixture(
                null,
                false,
                List.of(new LiveOutput(
                        ProcessOutputStream.STDOUT,
                        "hello token=raw super-secret https://bridge.example.test/tools/bridge-key/codex_change",
                        false
                ))
        );
        fixture.startAndOpen();

        fixture.receive("""
                {
                  "type": "job.request",
                  "jobId": "job_1",
                  "tool": "codex_change",
                  "message": "make it so",
                  "timeoutSeconds": 30
                }
                """);

        JsonNode transcript = json(fixture.webSocket().sentTexts().get(fixture.webSocket().sentTexts().size() - 2));
        JsonNode result = json(fixture.webSocket().lastText());

        assertEquals("job.transcript", transcript.get("type").asText());
        assertEquals("job_1", transcript.get("jobId").asText());
        assertEquals("stdout", transcript.get("streamType").asText());
        assertTrue(transcript.get("content").asText().contains("token=[redacted]"));
        assertTrue(transcript.get("content").asText().contains("[redacted tool url]"));
        assertFalse(transcript.get("content").asText().contains("raw"));
        assertFalse(transcript.get("content").asText().contains("super-secret"));
        assertEquals(false, transcript.get("truncated").asBoolean());
        assertEquals("job.result", result.get("type").asText());
    }

    @Test
    void jobRequestSendsBoundedTruncatedStderrTranscript() {
        Fixture fixture = fixture(
                null,
                false,
                List.of(new LiveOutput(
                        ProcessOutputStream.STDERR,
                        "x".repeat(AgentTranscriptSanitizer.MAX_CONTENT_CHARS + 100),
                        false
                ))
        );
        fixture.startAndOpen();

        fixture.receive("""
                {
                  "type": "job.request",
                  "jobId": "job_1",
                  "tool": "codex_change",
                  "message": "make it so",
                  "timeoutSeconds": 30
                }
                """);

        JsonNode transcript = json(fixture.webSocket().sentTexts().get(fixture.webSocket().sentTexts().size() - 2));

        assertEquals("job.transcript", transcript.get("type").asText());
        assertEquals("stderr", transcript.get("streamType").asText());
        assertEquals(AgentTranscriptSanitizer.MAX_CONTENT_CHARS, transcript.get("content").asText().length());
        assertTrue(transcript.get("content").asText().endsWith("[chunk truncated]"));
        assertEquals(true, transcript.get("truncated").asBoolean());
    }

    private Fixture fixture() {
        return fixture(null);
    }

    private Fixture pendingFixture() {
        return fixture(null, true);
    }

    private Fixture fixture(AgentJobExecutionResult jobResult) {
        return fixture(jobResult, false);
    }

    private Fixture fixture(AgentJobExecutionResult jobResult, boolean pendingJob) {
        return fixture(jobResult, pendingJob, List.of());
    }

    private Fixture fixture(AgentJobExecutionResult jobResult, boolean pendingJob, List<LiveOutput> liveOutputs) {
        FakeDialer dialer = new FakeDialer();
        FakeScheduler scheduler = new FakeScheduler();
        FakeStatusListener status = new FakeStatusListener();
        FakeJobHandler jobHandler = new FakeJobHandler(
                () -> dialer.lastWebSocket().sentTexts().size(),
                jobResult,
                pendingJob,
                liveOutputs
        );
        AgentStateStore stateStore = new AgentStateStore(tempDir.resolve("agent.json").toString());
        AgentState state = state();
        stateStore.save(state);
        AgentWebSocketClient client = new AgentWebSocketClient(
                config(),
                stateStore,
                () -> Map.of(
                        "codexCliAvailable", true,
                        "supportsReadonly", true,
                        "supportsVerify", true,
                        "supportsChange", true,
                        "workingDirectoryConfigured", true
                ),
                status,
                jobHandler,
                dialer,
                scheduler,
                FIXED_CLOCK,
                () -> 0
        );
        return new Fixture(client, stateStore, state, dialer, scheduler, status, jobHandler);
    }

    private record LiveOutput(ProcessOutputStream stream, String content, boolean truncated) {
    }

    private AgentConfig config() {
        return AgentConfig.fromEnvironment(Map.of(
                "CODEX_AGENT_ENABLED", "true",
                "CODEX_AGENT_BRIDGE_BASE_URL", "https://bridge.example.test",
                "CODEX_AGENT_STATE_FILE", tempDir.resolve("agent.json").toString(),
                "CODEX_AGENT_DISPLAY_NAME", "Dev Laptop",
                "CODEX_AGENT_CLIENT_VERSION", "client/1",
                "CODEX_AGENT_WORKING_DIRECTORY", tempDir.toString(),
                "CODEX_AGENT_HEARTBEAT_INTERVAL_SECONDS", "1",
                "CODEX_AGENT_RECONNECT_INITIAL_SECONDS", "2",
                "CODEX_AGENT_RECONNECT_MAX_SECONDS", "8"
        ), 1800);
    }

    private AgentState state() {
        return new AgentState(
                "ag_test",
                "super-secret",
                "codex",
                "https://bridge.example.test",
                "ws://bridge.example.test/agent/ws",
                "Dev Laptop",
                "2026-05-03T00:00:00Z",
                null
        );
    }

    private JsonNode json(String text) {
        return JsonUtil.fromJson(text, JsonNode.class);
    }

    private record Fixture(
            AgentWebSocketClient client,
            AgentStateStore stateStore,
            AgentState state,
            FakeDialer dialer,
            FakeScheduler scheduler,
            FakeStatusListener status,
            FakeJobHandler jobHandler
    ) {
        void startAndOpen() {
            client.start(state);
            scheduler.runNextOneShot();
            assertNotNull(dialer.lastListener());
            dialer.lastListener().onOpen(webSocket());
        }

        void receive(String text) {
            dialer.lastListener().onText(webSocket(), text, true);
        }

        FakeWebSocket webSocket() {
            return dialer.lastWebSocket();
        }
    }

    private static final class FakeDialer implements AgentWebSocketClient.Dialer {
        private URI lastUri;
        private Map<String, String> lastHeaders;
        private WebSocket.Listener lastListener;
        private FakeWebSocket lastWebSocket;
        private int connectCount;

        @Override
        public CompletableFuture<WebSocket> connect(
                URI uri,
                Map<String, String> headers,
                WebSocket.Listener listener
        ) {
            this.lastUri = uri;
            this.lastHeaders = new LinkedHashMap<>(headers);
            this.lastListener = listener;
            this.lastWebSocket = new FakeWebSocket();
            this.connectCount++;
            return CompletableFuture.completedFuture(lastWebSocket);
        }

        URI lastUri() {
            return lastUri;
        }

        Map<String, String> lastHeaders() {
            return lastHeaders;
        }

        WebSocket.Listener lastListener() {
            return lastListener;
        }

        FakeWebSocket lastWebSocket() {
            return lastWebSocket;
        }

        int connectCount() {
            return connectCount;
        }
    }

    private static final class FakeScheduler implements AgentWebSocketClient.TaskScheduler {
        private final List<ScheduledTask> tasks = new ArrayList<>();

        @Override
        public AgentWebSocketClient.Cancellable schedule(Runnable task, Duration delay) {
            ScheduledTask scheduledTask = new ScheduledTask(task, delay, false);
            tasks.add(scheduledTask);
            return scheduledTask;
        }

        @Override
        public AgentWebSocketClient.Cancellable scheduleAtFixedRate(
                Runnable task,
                Duration initialDelay,
                Duration period
        ) {
            ScheduledTask scheduledTask = new ScheduledTask(task, initialDelay, true);
            tasks.add(scheduledTask);
            return scheduledTask;
        }

        void runNextOneShot() {
            ScheduledTask task = tasks.stream()
                    .filter(candidate -> !candidate.periodic())
                    .filter(candidate -> !candidate.cancelled())
                    .filter(candidate -> !candidate.ran())
                    .findFirst()
                    .orElseThrow();
            task.run();
        }

        void runNextPeriodic() {
            latestPeriodic().run();
        }

        ScheduledTask latestPeriodic() {
            return tasks.stream()
                    .filter(ScheduledTask::periodic)
                    .reduce((first, second) -> second)
                    .orElseThrow();
        }

        Duration latestOneShotDelay() {
            return tasks.stream()
                    .filter(candidate -> !candidate.periodic())
                    .reduce((first, second) -> second)
                    .orElseThrow()
                    .delay();
        }

        private static final class ScheduledTask implements AgentWebSocketClient.Cancellable {
            private final Runnable task;
            private final Duration delay;
            private final boolean periodic;
            private boolean cancelled;
            private boolean ran;

            private ScheduledTask(Runnable task, Duration delay, boolean periodic) {
                this.task = task;
                this.delay = delay;
                this.periodic = periodic;
            }

            @Override
            public void cancel() {
                cancelled = true;
            }

            void run() {
                if (!cancelled) {
                    ran = true;
                    task.run();
                }
            }

            Duration delay() {
                return delay;
            }

            boolean periodic() {
                return periodic;
            }

            boolean cancelled() {
                return cancelled;
            }

            boolean ran() {
                return ran;
            }
        }
    }

    private static final class FakeJobHandler extends AgentJobHandler {
        private final IntSupplier sentCountSupplier;
        private final AgentJobExecutionResult result;
        private final boolean pending;
        private final List<LiveOutput> liveOutputs;
        private final CompletableFuture<AgentJobExecutionResult> pendingResult = new CompletableFuture<>();
        private int sentCountAtStart;

        private FakeJobHandler(
                IntSupplier sentCountSupplier,
                AgentJobExecutionResult result,
                boolean pending,
                List<LiveOutput> liveOutputs
        ) {
            this.sentCountSupplier = sentCountSupplier;
            this.result = result;
            this.pending = pending;
            this.liveOutputs = liveOutputs;
        }

        @Override
        public CompletableFuture<AgentJobExecutionResult> handleAsync(
                AgentRelayJobRequest request,
                ProcessOutputListener outputListener
        ) {
            sentCountAtStart = sentCountSupplier.getAsInt();
            if (outputListener != null) {
                for (LiveOutput output : liveOutputs) {
                    outputListener.onOutput(output.stream(), output.content(), output.truncated());
                }
            }
            if (pending) {
                return pendingResult;
            }
            if (result != null) {
                return CompletableFuture.completedFuture(result);
            }
            return CompletableFuture.completedFuture(AgentJobExecutionResult.completed(request.jobId(), "done", 0, false));
        }

        boolean acceptedWasSentBeforeStart() {
            return sentCountAtStart > 0;
        }

        void complete(AgentJobExecutionResult result) {
            pendingResult.complete(result);
        }
    }

    private static final class FakeStatusListener implements AgentWebSocketClient.StatusListener {
        private String connectedAt;
        private String disconnectedReason;
        private String disconnectedAt;
        private String seenAt;

        @Override
        public void connected(String timestamp) {
            connectedAt = timestamp;
        }

        @Override
        public void disconnected(String reason, String timestamp) {
            disconnectedReason = reason;
            disconnectedAt = timestamp;
        }

        @Override
        public void seen(String timestamp) {
            seenAt = timestamp;
        }

        String connectedAt() {
            return connectedAt;
        }

        String disconnectedReason() {
            return disconnectedReason;
        }

        String disconnectedAt() {
            return disconnectedAt;
        }

        String seenAt() {
            return seenAt;
        }
    }

    private static final class FakeWebSocket implements WebSocket {
        private final List<String> sentTexts = new ArrayList<>();
        private long requested;

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            sentTexts.add(data.toString());
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public void request(long n) {
            requested += n;
        }

        @Override
        public String getSubprotocol() {
            return "";
        }

        @Override
        public boolean isOutputClosed() {
            return false;
        }

        @Override
        public boolean isInputClosed() {
            return false;
        }

        @Override
        public void abort() {
        }

        List<String> sentTexts() {
            return sentTexts;
        }

        String lastText() {
            return sentTexts.get(sentTexts.size() - 1);
        }

        long requested() {
            return requested;
        }
    }
}
