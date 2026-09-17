package dev.aegis.documents.domain.classification;

import java.util.Objects;

/**
 * An automatic label proposal. {@code apply} expresses the caller's explicit policy decision that the value is
 * reliable enough to become effective without review; otherwise it stays a suggestion.
 */
public record Proposal(LabelRef label, Confidence confidence, boolean apply) {
    public Proposal {
        Objects.requireNonNull(label, "label");
    }
}
