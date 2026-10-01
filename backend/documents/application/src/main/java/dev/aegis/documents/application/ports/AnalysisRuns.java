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
    /**
     * Refusal or failure code when the requesting member may not use the configured AI provider, for example a
     * personal subscription that serves only its owner. An expected configuration state, not a failure.
     */
    String MEMBER_NOT_ALLOWED = "ai.provider.memberNotAllowed";
    /**
     * Failure code of a run stopped by the household's daily AI cost limit. While such a run is waiting for a retry,
     * it resumes on the next budget day ({@code nextAttemptAt}); an expected state, not a failure.
     */
    String DAILY_LIMIT = "budget.daily";

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
