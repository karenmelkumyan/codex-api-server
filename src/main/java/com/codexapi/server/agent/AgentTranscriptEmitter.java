package com.codexapi.server.agent;

import com.codexapi.server.process.ProcessOutputListener;
import com.codexapi.server.process.ProcessOutputStream;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;

final class AgentTranscriptEmitter implements ProcessOutputListener {
    static final int FLUSH_THRESHOLD_CHARS = 1_200;
    static final Duration FLUSH_DELAY = Duration.ofSeconds(1);

    private final String jobId;
    private final String agentSecret;
    private final AgentWebSocketClient.TaskScheduler scheduler;
    private final Sender sender;
    private final AgentTranscriptSanitizer sanitizer;
    private final Object lock = new Object();
    private final EnumMap<ProcessOutputStream, StreamBuffer> buffers = new EnumMap<>(ProcessOutputStream.class);

    private AgentWebSocketClient.Cancellable scheduledFlush;
    private boolean closed;

    AgentTranscriptEmitter(
            String jobId,
            String agentSecret,
            AgentWebSocketClient.TaskScheduler scheduler,
            Sender sender
    ) {
        this(jobId, agentSecret, scheduler, sender, new AgentTranscriptSanitizer());
    }

    AgentTranscriptEmitter(
            String jobId,
            String agentSecret,
            AgentWebSocketClient.TaskScheduler scheduler,
            Sender sender,
            AgentTranscriptSanitizer sanitizer
    ) {
        this.jobId = Objects.requireNonNull(jobId, "jobId");
        this.agentSecret = agentSecret;
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.sender = Objects.requireNonNull(sender, "sender");
        this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer");
        for (ProcessOutputStream stream : ProcessOutputStream.values()) {
            buffers.put(stream, new StreamBuffer());
        }
    }

    @Override
    public void onOutput(ProcessOutputStream stream, String content, boolean truncated) {
        if (stream == null) {
            return;
        }
        AgentTranscriptSanitizer.SanitizedTranscriptContent safeContent =
                sanitizer.sanitize(content, truncated, agentSecret == null ? List.of() : List.of(agentSecret));
        if (safeContent.content().isBlank() && !safeContent.truncated()) {
            return;
        }
        boolean flushNow;
        synchronized (lock) {
            if (closed) {
                return;
            }
            StreamBuffer buffer = buffers.get(stream);
            buffer.content().append(safeContent.content());
            buffer.truncated(buffer.truncated() || safeContent.truncated());
            flushNow = buffer.content().length() >= FLUSH_THRESHOLD_CHARS || safeContent.truncated();
            scheduleFlushLocked(flushNow ? Duration.ZERO : FLUSH_DELAY);
        }
    }

    void flushNow() {
        List<OutboundChunk> chunks = drain();
        for (OutboundChunk chunk : chunks) {
            sender.send(jobId, chunk.stream().value(), chunk.content(), chunk.truncated());
        }
    }

    void close() {
        synchronized (lock) {
            closed = true;
            if (scheduledFlush != null) {
                scheduledFlush.cancel();
                scheduledFlush = null;
            }
        }
    }

    private void scheduleFlushLocked(Duration delay) {
        if (closed) {
            return;
        }
        boolean immediate = delay.isZero() || delay.isNegative();
        if (scheduledFlush != null) {
            if (!immediate) {
                return;
            }
            scheduledFlush.cancel();
            scheduledFlush = null;
        }
        scheduledFlush = scheduler.schedule(this::flushNow, delay);
    }

    private List<OutboundChunk> drain() {
        List<OutboundChunk> chunks = new ArrayList<>();
        synchronized (lock) {
            scheduledFlush = null;
            for (ProcessOutputStream stream : ProcessOutputStream.values()) {
                StreamBuffer buffer = buffers.get(stream);
                while (!buffer.content().isEmpty()) {
                    int length = Math.min(buffer.content().length(), AgentTranscriptSanitizer.MAX_CONTENT_CHARS);
                    String content = buffer.content().substring(0, length);
                    buffer.content().delete(0, length);
                    boolean chunkTruncated = buffer.truncated() && buffer.content().isEmpty();
                    chunks.add(new OutboundChunk(stream, content, chunkTruncated));
                }
                buffer.truncated(false);
            }
        }
        return chunks;
    }

    interface Sender {
        void send(String jobId, String streamType, String content, boolean truncated);
    }

    private record OutboundChunk(ProcessOutputStream stream, String content, boolean truncated) {
    }

    private static final class StreamBuffer {
        private final StringBuilder content = new StringBuilder();
        private boolean truncated;

        private StringBuilder content() {
            return content;
        }

        private boolean truncated() {
            return truncated;
        }

        private void truncated(boolean truncated) {
            this.truncated = truncated;
        }
    }
}
