package dev.aegis.documents.application.api;

import dev.aegis.shared.Actor;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Bulk classification runs; both members see every run of the household. */
public interface BulkAnalysisQueries {
    /** A finished run whose documents still need a member; the review inbox shows it as one card (issue #115). */
    record Attention(UUID id, String state, Instant finishedAt, int total, int classified, int needsReview,
            int failed) {
    }

    /** The most recent runs, newest first; a running run is always among them. */
    List<BulkAnalysisView> recent(Actor actor);

    BulkAnalysisView get(Actor actor, UUID id);

    /**
     * Recently finished runs with documents that still need a member (a review or a failed run). Open to household
     * readers, because the review reconciler reads it (ADR-0009).
     */
    List<Attention> attention(Actor actor);
}
