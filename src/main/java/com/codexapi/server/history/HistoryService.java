package com.codexapi.server.history;

import com.codexapi.server.session.SessionStore;

import java.util.List;

public final class HistoryService {
    private final SessionStore sessionStore;

    public HistoryService(SessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    public List<ExecutionRecord> list(String sessionId) {
        return sessionStore.history(sessionId);
    }

    public void add(ExecutionRecord record) {
        sessionStore.addExecutionRecord(record);
    }
}
