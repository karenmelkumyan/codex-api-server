package com.codexapi.server.codex;

import java.util.List;

public record CodexCommand(String executable, List<String> args) {
    public CodexCommand {
        args = List.copyOf(args);
    }
}
