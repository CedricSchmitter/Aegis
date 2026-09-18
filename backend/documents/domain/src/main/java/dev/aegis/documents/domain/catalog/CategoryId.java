package dev.aegis.documents.domain.catalog;

import dev.aegis.shared.InvalidInputException;
import java.util.Objects;
import java.util.UUID;

/** Stable identity of a folder in the document tree; survives renames and moves. */
public record CategoryId(UUID value) {
    public CategoryId {
        Objects.requireNonNull(value, "category id");
    }

    public static CategoryId random() {
        return new CategoryId(UUID.randomUUID());
    }

    public static CategoryId of(String value) {
        try {
            return new CategoryId(UUID.fromString(value));
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new InvalidInputException("validation.failed", "Invalid CategoryId value");
        }
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
