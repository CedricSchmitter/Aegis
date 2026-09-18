package dev.aegis.documents.domain.classification;

import dev.aegis.documents.domain.catalog.Facet;
import dev.aegis.documents.domain.catalog.LabelId;
import java.util.Objects;

/** A label as seen by a classification: its identity and facet. */
public record LabelRef(LabelId id, Facet facet) {
    public LabelRef {
        Objects.requireNonNull(id, "label id");
        Objects.requireNonNull(facet, "facet");
    }
}
