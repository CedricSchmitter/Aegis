package dev.aegis.shared;

import java.util.Objects;
import java.util.UUID;

/** Stable Aegis identity of a captured document; independent of external Paperless ids. */
public record DocumentId(UUID value) {
    public DocumentId {
        Objects.requireNonNull(value, "document id");
    }

    public static DocumentId random() {
        return new DocumentId(UUID.randomUUID());
    }

    public static DocumentId of(String value) {
        return new DocumentId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
