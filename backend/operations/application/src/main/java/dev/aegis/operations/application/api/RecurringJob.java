package dev.aegis.operations.application.api;

import java.time.Duration;
import java.util.Objects;

/**
 * A recurring background job of the installation and the time between two of its runs, as the job is scheduled.
 * The composition root declares the jobs; the status page reports whether each still runs (issue #92, ADR-0013).
 */
public record RecurringJob(String name, Duration interval) {
    public RecurringJob {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(interval, "interval");
        if (name.isBlank() || interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("a recurring job needs a name and a positive interval");
        }
    }
}
