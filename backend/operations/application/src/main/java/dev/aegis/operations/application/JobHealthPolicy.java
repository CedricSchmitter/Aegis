package dev.aegis.operations.application;

import dev.aegis.operations.application.api.JobHealth;
import dev.aegis.operations.application.api.RecurringJob;
import dev.aegis.operations.application.ports.RecurringJobStateSource.RecurringJobState;
import java.time.Duration;
import java.time.Instant;

/**
 * Decides whether a recurring job still runs (ADR-0013). A job runs one interval after its previous run ended, so
 * a healthy job succeeded within about one interval. One failed run makes it FAILING; it becomes STALE once its last
 * success is older than two intervals plus {@link #GRACE}, that is when the run after the failed one did not
 * succeed either. The grace period covers run time and a restart during a deployment. A job that never succeeded
 * is stale after two failures in a row or when its first run is overdue by more than an interval plus the grace.
 */
final class JobHealthPolicy {
    static final Duration GRACE = Duration.ofMinutes(10);

    private JobHealthPolicy() {
    }

    static Duration staleAfter(Duration interval) {
        return interval.multipliedBy(2).plus(GRACE);
    }

    static JobHealth evaluate(RecurringJob job, RecurringJobState state, Instant now) {
        if (state == null) {
            return new JobHealth(job.name(), JobHealth.NOT_SCHEDULED, job.interval(), null, null, 0, null);
        }
        return new JobHealth(job.name(), state(job.interval(), state, now), job.interval(), state.lastSuccessAt(),
                state.lastFailureAt(), state.consecutiveFailures(), state.nextRunAt());
    }

    private static String state(Duration interval, RecurringJobState state, Instant now) {
        boolean failing = state.consecutiveFailures() > 0;
        if (state.lastSuccessAt() != null) {
            if (now.isAfter(state.lastSuccessAt().plus(staleAfter(interval)))) {
                return JobHealth.STALE;
            }
            return failing ? JobHealth.FAILING : JobHealth.OK;
        }
        if (state.consecutiveFailures() >= 2) {
            return JobHealth.STALE;
        }
        if (failing) {
            return JobHealth.FAILING;
        }
        boolean overdue = state.nextRunAt() != null && now.isAfter(state.nextRunAt().plus(interval).plus(GRACE));
        return overdue ? JobHealth.STALE : JobHealth.PENDING;
    }
}
