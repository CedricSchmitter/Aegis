package dev.aegis.operations.application.ports;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/** The job scheduler's persisted state of recurring jobs. It reads run times and failure counts, never job data. */
public interface RecurringJobStateSource {
    /** State by job name for the named jobs that have a stored schedule; a job without one is absent. */
    Map<String, RecurringJobState> states(Collection<String> names);

    /** Times are null until the job first succeeded or failed; {@code nextRunAt} is when it runs next. */
    record RecurringJobState(Instant lastSuccessAt, Instant lastFailureAt, int consecutiveFailures,
            Instant nextRunAt) {
    }
}
