package dev.aegis.documents.domain.classification;

import dev.aegis.documents.domain.catalog.Facet;
import dev.aegis.documents.domain.catalog.LabelId;
import dev.aegis.shared.MemberId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One label on one document with its provenance. Manual assignments are always confirmed; automatic ones carry an
 * optional confidence and source/run reference. {@code decidedBy} is the member who made or reviewed the decision.
 */
public record LabelAssignment(LabelId labelId, Facet facet, AssignmentOrigin origin, ReviewState state,
        Confidence confidence, String sourceRef, MemberId decidedBy, Instant decidedAt) {
    public static final int MAX_SOURCE_REF = 200;

    public LabelAssignment {
        Objects.requireNonNull(labelId, "label id");
        Objects.requireNonNull(facet, "facet");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(decidedAt, "decidedAt");
        if (origin == AssignmentOrigin.MANUAL && state != ReviewState.CONFIRMED) {
            throw new IllegalArgumentException("manual assignments are always confirmed");
        }
        if (sourceRef != null && sourceRef.length() > MAX_SOURCE_REF) {
            sourceRef = sourceRef.substring(0, MAX_SOURCE_REF);
        }
    }

    static LabelAssignment manual(LabelRef label, MemberId by, Instant now) {
        return new LabelAssignment(label.id(), label.facet(), AssignmentOrigin.MANUAL, ReviewState.CONFIRMED, null,
                null, Objects.requireNonNull(by), now);
    }

    LabelAssignment reviewed(ReviewState newState, MemberId by, Instant now) {
        return new LabelAssignment(labelId, facet, origin, newState, confidence, sourceRef, by, now);
    }

    LabelAssignment relabeled(LabelId newLabel) {
        return new LabelAssignment(newLabel, facet, origin, state, confidence, sourceRef, decidedBy, decidedAt);
    }

    public boolean effective() {
        return state.effective();
    }

    public Optional<Confidence> confidenceValue() {
        return Optional.ofNullable(confidence);
    }
}
