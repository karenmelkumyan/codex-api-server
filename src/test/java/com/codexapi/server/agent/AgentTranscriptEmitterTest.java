package com.codexapi.server.agent;

import com.codexapi.server.process.ProcessOutputStream;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AgentTranscriptEmitterTest {

    @Test
    void buffersSanitizesBoundsAndFlushesTranscriptChunks() {
        FakeScheduler scheduler = new FakeScheduler();
        List<SentChunk> sent = new ArrayList<>();
        AgentTranscriptEmitter emitter = new AgentTranscriptEmitter(
                "job_1",
                "agent-secret",
                scheduler,
                (jobId, streamType, content, truncated) ->
                        sent.add(new SentChunk(jobId, streamType, content, truncated))
        );

        emitter.onOutput(
                ProcessOutputStream.STDOUT,
                "hello token=raw agent-secret https://bridge.example.test/tools/bridge-key/codex_change",
                false
        );

        assertEquals(0, sent.size());
        scheduler.runLatest();

        assertEquals(1, sent.size());
        SentChunk chunk = sent.get(0);
        assertEquals("job_1", chunk.jobId());
        assertEquals("stdout", chunk.streamType());
        assertTrue(chunk.content().contains("token=[redacted]"));
        assertTrue(chunk.content().contains("[redacted tool url]"));
        assertFalse(chunk.content().contains("raw"));
        assertFalse(chunk.content().contains("agent-secret"));
        assertFalse(chunk.truncated());
    }

    @Test
    void visiblyTruncatesOversizedTranscriptChunks() {
        FakeScheduler scheduler = new FakeScheduler();
        List<SentChunk> sent = new ArrayList<>();
        AgentTranscriptEmitter emitter = new AgentTranscriptEmitter(
                "job_1",
                null,
                scheduler,
                (jobId, streamType, content, truncated) ->
                        sent.add(new SentChunk(jobId, streamType, content, truncated))
        );

        emitter.onOutput(ProcessOutputStream.STDERR, "x".repeat(AgentTranscriptSanitizer.MAX_CONTENT_CHARS + 100), false);
        scheduler.runLatest();

        assertEquals(1, sent.size());
        assertEquals("stderr", sent.get(0).streamType());
        assertEquals(AgentTranscriptSanitizer.MAX_CONTENT_CHARS, sent.get(0).content().length());
        assertTrue(sent.get(0).content().endsWith("[chunk truncated]"));
        assertTrue(sent.get(0).truncated());
    }

    private record SentChunk(String jobId, String streamType, String content, boolean truncated) {
    }

    private static final class FakeScheduler implements AgentWebSocketClient.TaskScheduler {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public AgentWebSocketClient.Cancellable schedule(Runnable task, Duration delay) {
            tasks.add(task);
            return () -> {
            };
        }

        @Override
        public AgentWebSocketClient.Cancellable scheduleAtFixedRate(
                Runnable task,
                Duration initialDelay,
                Duration period
        ) {
            tasks.add(task);
            return () -> {
            };
        }

        void runLatest() {
            tasks.get(tasks.size() - 1).run();
        }
    }
}
