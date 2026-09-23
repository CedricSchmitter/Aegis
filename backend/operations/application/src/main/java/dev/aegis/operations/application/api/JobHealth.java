package dev.aegis.operations.application.api;

import java.time.Duration;
import java.time.Instant;

/**
 * Whether one recurring background job still runs, from the scheduler's persisted state (ADR-0013). {@code state} is
 * OK, FAILING (its latest run failed), STALE (no success for longer than two intervals plus a grace period),
 * PENDING (scheduled, first run not due yet), NOT_SCHEDULED (no schedule stored) or UNKNOWN (the state could not
 * be read). It names the job only and never carries job data or household content.
 */
public record JobHealth(String name, String state, Duration interval, Instant lastSuccessAt, Instant lastFailureAt,
        int consecutiveFailures, Instant nextRunAt) {
    public static final String OK = "OK";
    public static final String FAILING = "FAILING";
    public static final String STALE = "STALE";
    public static final String PENDING = "PENDING";
    public static final String NOT_SCHEDULED = "NOT_SCHEDULED";
    public static final String UNKNOWN = "UNKNOWN";
}
