package dev.aegis.assistant.application.api;

import java.util.List;
import java.util.Map;

/**
 * Request for a bounded AI run. {@code workload} is a workload name such as {@code DOCUMENT_CLASSIFICATION}.
 * {@code idempotencyKey} identifies the work (typically workload plus source versions): while a run with the key is
 * active it is returned instead of a new one, and a succeeded run is returned unless {@code reprocess} is set.
 * {@code parameters} are small, non-sensitive settings; content is read by the workload through public contracts.
 */
public record RunRequest(String workload, String idempotencyKey, List<SourceVersion> sources,
        Map<String, String> parameters, boolean reprocess) {
    public RunRequest {
        sources = sources == null ? List.of() : List.copyOf(sources);
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
}
