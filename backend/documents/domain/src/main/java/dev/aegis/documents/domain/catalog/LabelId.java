package dev.aegis.documents.domain.catalog;

import dev.aegis.shared.InvalidInputException;
import java.util.Objects;
import java.util.UUID;

/** Stable identity of a vocabulary entry; survives renames, merges (as a redirect) and export/restore. */
public record LabelId(UUID value) {
    public LabelId {
        Objects.requireNonNull(value, "label id");
    }

    public static LabelId random() {
        return new LabelId(UUID.randomUUID());
    }

    public static LabelId of(String value) {
        try {
            return new LabelId(UUID.fromString(value));
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new InvalidInputException("validation.failed", "Invalid LabelId value");
        }
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
