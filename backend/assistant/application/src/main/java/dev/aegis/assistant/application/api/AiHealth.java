package dev.aegis.assistant.application.api;

import java.time.Instant;
import java.util.List;

/**
 * Operational summary for the status page: whether credentials exist, the last successful run and, for every
 * currently configured model, its most recent succeeded or failed run. A model without such a run is unverified.
 */
public record AiHealth(boolean configured, Instant lastSuccessAt, List<ModelHealth> models) {

    /** {@code lastState} and {@code lastFailureCategory} are null while the model has no finished run. */
    public record ModelHealth(String provider, String model, String lastState, String lastFailureCategory,
            Instant lastFinishedAt) {
    }

    public AiHealth {
        models = List.copyOf(models);
    }
}
