package dev.aegis.contracts.domain;

import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * How a fact came to be known: who or what stated it, from which document, and with which reported confidence.
 * An automatic observation must name its source document and its run; a member may state a fact without one.
 */
public record FactProvenance(FactOrigin origin, SourceReference source, Confidence confidence, String runRef,
        MemberId observedBy, Instant observedAt) {

    public FactProvenance {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(observedAt, "observed at");
        if (origin == FactOrigin.AUTOMATIC && source == null) {
            throw new InvalidInputException("contract.source.missing",
                    "An extracted fact must reference its source document");
        }
        if (origin == FactOrigin.MANUAL && (confidence != null || runRef != null)) {
            throw new InvalidInputException("contract.provenance.invalid",
                    "A member's statement carries neither confidence nor a run reference");
        }
        if (origin == FactOrigin.MANUAL && observedBy == null) {
            throw new InvalidInputException("contract.provenance.invalid", "A member's statement needs the member");
        }
    }

    public static FactProvenance manual(MemberId by, SourceReference source, Instant at) {
        return new FactProvenance(FactOrigin.MANUAL, source, null, null, by, at);
    }

    public static FactProvenance automatic(SourceReference source, Confidence confidence, String runRef,
            Instant at) {
        return new FactProvenance(FactOrigin.AUTOMATIC, source, confidence, runRef, null, at);
    }

    public Optional<SourceReference> sourceReference() {
        return Optional.ofNullable(source);
    }
}
