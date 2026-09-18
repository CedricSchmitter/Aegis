package dev.aegis.assistant.domain;

/** Visible lifecycle of a run. Terminal states never change again. */
public enum RunState {
    QUEUED,
    RUNNING,
    WAITING_RETRY,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }

    public boolean active() {
        return !terminal();
    }
}
