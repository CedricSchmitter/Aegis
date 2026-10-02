package dev.aegis.documents.application;

import java.time.Instant;
import java.util.UUID;

/**
 * One step of a running bulk analysis, executed by its persisted job: follow the runs in flight, request
 * the next bounded batch and end the run when nothing is left. A step is one transaction and safe to repeat.
 */
public interface BulkAnalysisDriver {
    sealed interface Step permits Continue, Done {
    }

    /** Run the next step at {@code at}. */
    record Continue(Instant at) implements Step {
    }

    /** The run ended or no longer exists. */
    record Done() implements Step {
    }

    Step advance(UUID bulkId);
}
