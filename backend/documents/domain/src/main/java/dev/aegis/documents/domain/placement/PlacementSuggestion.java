package dev.aegis.documents.domain.placement;

import dev.aegis.shared.DocumentId;
import java.time.Instant;
import java.util.Objects;

/**
 * A folder placement waiting for a member's decision: where the classifier would file the document, with the run
 * that proposed it and the model's own confidence estimate. A document has at most one pending suggestion, always
 * the one of its latest finished run.
 *
 * @param generation the analysis generation that produced it, so an older run's answer cannot revive it
 * @param evidence a quote from the document text supporting the placement, or {@code null}
 */
public record PlacementSuggestion(DocumentId documentId, int generation, PlacementTarget target, Double confidence,
        String evidence, String runRef, String model, Instant createdAt) {
    public PlacementSuggestion {
        Objects.requireNonNull(documentId, "document id");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(createdAt, "created at");
    }

    public String key() {
        return target.key();
    }
}
