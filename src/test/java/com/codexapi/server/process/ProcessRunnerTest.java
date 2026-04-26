package com.codexapi.server.process;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProcessRunnerTest {
    @Test
    void capturesStdoutAndStderrWithTruncation() {
        ProcessResult result = new ProcessRunner().run(
                javaCommand("output"),
                Duration.ofSeconds(5),
                null,
                4,
                3
        );

        assertEquals(0, result.exitCode());
        assertEquals("abcd", result.stdout());
        assertEquals("012", result.stderr());
        assertTrue(result.stdoutTruncated());
        assertTrue(result.stderrTruncated());
    }

    @Test
    void timesOutProcess() {
        ProcessResult result = new ProcessRunner().run(javaCommand("sleep"), Duration.ofMillis(100));

        assertTrue(result.timedOut());
    }

    @Test
    void writesStdinToProcess() {
        ProcessResult result = new ProcessRunner().run(
                javaCommand("stdin"),
                Duration.ofSeconds(5),
                null,
                100,
                100,
                "hello stdin"
        );

        assertEquals(0, result.exitCode());
        assertEquals("hello stdin", result.stdout());
    }

    private List<String> javaCommand(String mode) {
        return List.of(
                javaExecutable(),
                "-cp",
                System.getProperty("java.class.path"),
                Fixture.class.getName(),
                mode
        );
    }

    private String javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    public static final class Fixture {
        public static void main(String[] args) throws Exception {
            switch (args[0]) {
                case "output" -> {
                    System.out.print("abcdefghij");
                    System.err.print("0123456789");
                }
                case "sleep" -> Thread.sleep(5_000);
                case "stdin" -> System.out.print(new String(System.in.readAllBytes(), StandardCharsets.UTF_8));
                default -> throw new IllegalArgumentException(args[0]);
            }
        }
    }
}
