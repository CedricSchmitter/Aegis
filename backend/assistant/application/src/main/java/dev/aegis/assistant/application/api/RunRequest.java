package dev.aegis.assistant.application.api;

import java.util.List;
import java.util.Map;

/**
 * Request for a bounded AI run. {@code workload} is a workload name such as {@code DOCUMENT_CLASSIFICATION}.
 * {@code idempotencyKey} identifies one unit of work: the workload, its sources with their versions and its parameters.
 * While a run with the key is active it is returned instead of a new one, and a succeeded run is returned unless
 * {@code reprocess} is set. Reusing a key for different work is rejected with {@code ai.run.keyConflict}.
 * {@code parameters} are small, non-sensitive settings; content is read by the workload through public contracts.
 * {@code family} chooses a model family of the catalog for this run only; without it the run uses the household's
 * family for the workload.
 */
public record RunRequest(String workload, String idempotencyKey, List<SourceVersion> sources,
        Map<String, String> parameters, boolean reprocess, String family) {
    public RunRequest {
        sources = sources == null ? List.of() : List.copyOf(sources);
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        family = family == null || family.isBlank() ? null : family.strip();
    }

    /** A run with the household's model family for the workload. */
    public RunRequest(String workload, String idempotencyKey, List<SourceVersion> sources,
            Map<String, String> parameters, boolean reprocess) {
        this(workload, idempotencyKey, sources, parameters, reprocess, null);
    }
}
