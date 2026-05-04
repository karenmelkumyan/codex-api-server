package com.codexapi.server.process;

@FunctionalInterface
public interface ProcessOutputListener {
    void onOutput(ProcessOutputStream stream, String content, boolean truncated);

    static ProcessOutputListener noop() {
        return (stream, content, truncated) -> {
        };
    }
}
