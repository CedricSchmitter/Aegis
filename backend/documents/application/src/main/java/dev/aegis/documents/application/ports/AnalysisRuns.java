package dev.aegis.documents.application.ports;

import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Outbound port to the AI execution boundary (ADR-0003) for document analysis runs. A request takes part in the
 * caller's transaction, so the analysis state and the durable run job commit together. The run's key identifies one
 * unit of work: the document and the analysis generation.
 */
public interface AnalysisRuns {
    String SOURCE_CLASSIFICATION = "documentClassification";
    String SOURCE_ANALYSIS = "documentAnalysis";

    static String key(DocumentId id, int generation) {
        return "document-classification:" + id + ":g" + generation;
    }

    sealed interface Outcome permits Requested, Unavailable {
    }

    record Requested(UUID runId) implements Outcome {
    }

    /** The run cannot be queued now; {@code code} is a stable reason such as {@code ai.notConfigured}. */
    record Unavailable(String code) implements Outcome {
    }

    /** Latest run of a key. {@code state} is the run state (QUEUED, RUNNING, WAITING_RETRY, SUCCEEDED, ...). */
    record RunStatus(UUID runId, String state, String failureCategory, String failureCode, Instant nextAttemptAt,
            Instant finishedAt) {
        public boolean active() {
            return "QUEUED".equals(state) || "RUNNING".equals(state) || "WAITING_RETRY".equals(state);
        }
    }

    /** Requests the run on behalf of {@code requester}; never throws for an unavailable AI configuration. */
    Outcome request(Actor requester, DocumentId id, int generation, long classificationVersion);

    /** Latest run per key; keys without a run are absent. */
    Map<String, RunStatus> latest(Collection<String> keys);
}
