package dev.aegis.assistant.domain;

/** Kinds of audit steps that make the progress of a run visible. */
public enum StepKind {
    ATTEMPT_STARTED,
    MODEL_CALL,
    TOOL_CALL,
    TOOL_DENIED,
    ATTEMPT_FAILED,
    CANCELLED,
    COMPLETED
}
