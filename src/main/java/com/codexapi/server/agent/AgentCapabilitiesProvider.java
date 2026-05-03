package com.codexapi.server.agent;

import com.codexapi.server.config.Config;
import com.codexapi.server.process.ProcessResult;
import com.codexapi.server.process.ProcessRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AgentCapabilitiesProvider {
    private final Config config;
    private final ProcessRunner processRunner;

    public AgentCapabilitiesProvider(Config config, ProcessRunner processRunner) {
        this.config = config;
        this.processRunner = processRunner;
    }

    public Map<String, Object> capabilities() {
        ProcessResult result = processRunner.run(
                List.of(config.codexCliPath(), "--version"),
                Duration.ofSeconds(config.execDefaultTimeoutSeconds())
        );
        boolean codexCliAvailable = result.started()
                && !result.timedOut()
                && result.exitCode() != null
                && result.exitCode() == 0;

        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("codexCliAvailable", codexCliAvailable);
        if (codexCliAvailable && !result.stdout().isBlank()) {
            capabilities.put("codexCliVersion", result.stdout().trim());
        }
        capabilities.put("supportsReadonly", true);
        capabilities.put("supportsVerify", true);
        capabilities.put("supportsChange", true);
        capabilities.put("workingDirectoryConfigured", workingDirectoryConfigured());
        return capabilities;
    }

    private boolean workingDirectoryConfigured() {
        String workingDirectory = config.agent().workingDirectory();
        return workingDirectory != null
                && !workingDirectory.isBlank()
                && Files.isDirectory(Path.of(workingDirectory));
    }
}
