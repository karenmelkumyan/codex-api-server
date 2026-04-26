package com.codexapi.server.process;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ProcessRunner {
    public static final int DEFAULT_STDOUT_BYTES = 1_000_000;
    public static final int DEFAULT_STDERR_BYTES = 1_000_000;
    private static final Duration STREAM_READ_TIMEOUT = Duration.ofSeconds(1);

    public ProcessResult run(List<String> command, Duration timeout) {
        return run(command, timeout, null, DEFAULT_STDOUT_BYTES, DEFAULT_STDERR_BYTES);
    }

    public ProcessResult run(List<String> command, Duration timeout, Path workingDirectory) {
        return run(command, timeout, workingDirectory, DEFAULT_STDOUT_BYTES, DEFAULT_STDERR_BYTES);
    }

    public ProcessResult run(
            List<String> command,
            Duration timeout,
            Path workingDirectory,
            int maxStdoutBytes,
            int maxStderrBytes
    ) {
        return run(command, timeout, workingDirectory, maxStdoutBytes, maxStderrBytes, null);
    }

    public ProcessResult run(
            List<String> command,
            Duration timeout,
            Path workingDirectory,
            int maxStdoutBytes,
            int maxStderrBytes,
            String stdinText
    ) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("Command must not be empty");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Timeout must be greater than zero");
        }
        if (maxStdoutBytes < 1) {
            throw new IllegalArgumentException("maxStdoutBytes must be greater than zero");
        }
        if (maxStderrBytes < 1) {
            throw new IllegalArgumentException("maxStderrBytes must be greater than zero");
        }

        long startNanos = System.nanoTime();
        Process process;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            if (workingDirectory != null) {
                processBuilder.directory(workingDirectory.toFile());
            }
            process = processBuilder.start();
        } catch (IOException exception) {
            return ProcessResult.failedToStart(command, elapsedMillis(startNanos), exception.getMessage());
        }

        ExecutorService streamReaders = Executors.newFixedThreadPool(3);
        CompletableFuture<CapturedOutput> stdout = CompletableFuture.supplyAsync(
                () -> readLimited(process.getInputStream(), maxStdoutBytes),
                streamReaders
        );
        CompletableFuture<CapturedOutput> stderr = CompletableFuture.supplyAsync(
                () -> readLimited(process.getErrorStream(), maxStderrBytes),
                streamReaders
        );
        CompletableFuture<Void> stdin = CompletableFuture.runAsync(
                () -> writeStdin(process.getOutputStream(), stdinText),
                streamReaders
        );

        try {
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                Integer exitCode = waitForForcedExit(process);
                CapturedOutput capturedStdout = readFuture(stdout);
                CapturedOutput capturedStderr = readFuture(stderr);
                return ProcessResult.timedOut(
                        command,
                        capturedStdout.value(),
                        capturedStderr.value(),
                        capturedStdout.truncated(),
                        capturedStderr.truncated(),
                        exitCode,
                        elapsedMillis(startNanos)
                );
            }

            CapturedOutput capturedStdout = readFuture(stdout);
            CapturedOutput capturedStderr = readFuture(stderr);
            return ProcessResult.completed(
                    command,
                    capturedStdout.value(),
                    capturedStderr.value(),
                    capturedStdout.truncated(),
                    capturedStderr.truncated(),
                    process.exitValue(),
                    elapsedMillis(startNanos)
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            CapturedOutput capturedStdout = readFuture(stdout);
            CapturedOutput capturedStderr = readFuture(stderr);
            return ProcessResult.interrupted(
                    command,
                    capturedStdout.value(),
                    capturedStderr.value(),
                    capturedStdout.truncated(),
                    capturedStderr.truncated(),
                    elapsedMillis(startNanos)
            );
        } finally {
            waitForStdin(stdin);
            streamReaders.shutdownNow();
        }
    }

    private static Integer waitForForcedExit(Process process) throws InterruptedException {
        if (process.waitFor(1, TimeUnit.SECONDS)) {
            return process.exitValue();
        }
        return null;
    }

    private static CapturedOutput readLimited(InputStream inputStream, int maxBytes) {
        try {
            ByteArrayOutputStream capturedBytes = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
            byte[] buffer = new byte[8192];
            int remainingBytes = maxBytes;
            boolean truncated = false;
            int bytesRead;

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                if (remainingBytes > 0) {
                    int bytesToCapture = Math.min(bytesRead, remainingBytes);
                    capturedBytes.write(buffer, 0, bytesToCapture);
                    remainingBytes -= bytesToCapture;
                    if (bytesToCapture < bytesRead) {
                        truncated = true;
                    }
                } else {
                    truncated = true;
                }
            }

            return new CapturedOutput(capturedBytes.toString(StandardCharsets.UTF_8), truncated);
        } catch (IOException exception) {
            return new CapturedOutput("", false);
        }
    }

    private static void writeStdin(OutputStream outputStream, String stdinText) {
        try (outputStream) {
            if (stdinText != null) {
                outputStream.write(stdinText.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException exception) {
            // The process may exit before reading stdin; callers inspect process output and exit status.
        }
    }

    private static void waitForStdin(CompletableFuture<Void> future) {
        try {
            future.get(STREAM_READ_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException exception) {
            // Best effort cleanup only.
        }
    }

    private static CapturedOutput readFuture(CompletableFuture<CapturedOutput> future) {
        try {
            return future.get(STREAM_READ_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new CapturedOutput("", false);
        } catch (ExecutionException | TimeoutException exception) {
            return new CapturedOutput("", false);
        }
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private record CapturedOutput(String value, boolean truncated) {
    }
}
