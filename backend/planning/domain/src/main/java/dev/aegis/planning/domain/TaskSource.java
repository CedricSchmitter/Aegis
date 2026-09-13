package dev.aegis.planning.domain;

import dev.aegis.shared.DocumentId;
import java.util.Objects;

/** Link from a task to the record it stems from. Only documents exist as sources in this release. */
public record TaskSource(DocumentId documentId) {
    public TaskSource {
        Objects.requireNonNull(documentId, "document id");
    }
}
