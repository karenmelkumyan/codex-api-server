package com.codexapi.server.process;

public enum ProcessOutputStream {
    STDOUT("stdout"),
    STDERR("stderr");

    private final String value;

    ProcessOutputStream(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
