package dev.aegis.planning.domain;

/** Lifecycle of a task. Completion is separate from notification delivery and read state. */
public enum TaskStatus {
    OPEN,
    DONE,
    /** An automatic occurrence superseded by corrected facts or a resolved contract. */
    CANCELLED
}
