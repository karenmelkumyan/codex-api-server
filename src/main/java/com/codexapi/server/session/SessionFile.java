package com.codexapi.server.session;

import com.codexapi.server.history.ExecutionRecord;

import java.util.List;

public record SessionFile(List<Session> sessions, List<ExecutionRecord> history) {
    public SessionFile {
        sessions = sessions == null ? List.of() : List.copyOf(sessions);
        history = history == null ? List.of() : List.copyOf(history);
    }
}
