package dev.aegis.operations.application.api;

import java.time.Instant;

/**
 * Whether the external backup works (ADR-0015), from the state file the backup script writes. {@code state}
 * is OK, RUNNING (a run is active), FAILED (the latest attempt failed, {@code failureCode} says why), STALE (no success
 * for more than 26 hours), INTERRUPTED (a run started but never finished), NOT_CONFIGURED (no verified destination or
 * no success recorded yet) or UNKNOWN (the state file cannot be read). {@code failureCode} is a stable English code
 * such as {@code destination.notMounted}; it never carries command output or household content.
 */
public record BackupHealth(String state, String failureCode, Instant lastSuccessAt, Instant lastAttemptAt,
        Instant runningSince) {
    public static final String OK = "OK";
    public static final String RUNNING = "RUNNING";
    public static final String FAILED = "FAILED";
    public static final String STALE = "STALE";
    public static final String INTERRUPTED = "INTERRUPTED";
    public static final String NOT_CONFIGURED = "NOT_CONFIGURED";
    public static final String UNKNOWN = "UNKNOWN";

    /** The failure code of a state file that exists but cannot be read or parsed. */
    public static final String STATE_UNREADABLE = "state.unreadable";
}
