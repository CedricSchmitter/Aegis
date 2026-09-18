package dev.aegis.assistant.domain;

import java.util.Objects;

/**
 * Version of an input the run analyzed, for example a document at classification version 3. Results are applied
 * only while every source still has this version.
 */
public record SourceRef(String kind, String id, long version) {
    public SourceRef {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
        if (kind.isBlank() || id.isBlank() || version < 0) {
            throw new IllegalArgumentException("source kind, id and version are required");
        }
    }
}
