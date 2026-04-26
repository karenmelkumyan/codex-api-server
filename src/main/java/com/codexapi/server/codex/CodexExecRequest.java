package com.codexapi.server.codex;

public record CodexExecRequest(
        String prompt,
        Integer timeoutSeconds,
        String model,
        String profile,
        String sandbox,
        String approvalPolicy,
        Boolean ephemeral,
        Boolean skipGitRepoCheck
) {
}
