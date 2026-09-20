package dev.aegis.contracts.domain;

import dev.aegis.shared.InvalidInputException;
import java.util.Objects;
import java.util.UUID;

/** Stable identity of one observation of a contract fact; referenced by corrections and supersessions. */
public record FactId(UUID value) {
    public FactId {
        Objects.requireNonNull(value, "fact id");
    }

    public static FactId random() {
        return new FactId(UUID.randomUUID());
    }

    public static FactId of(String value) {
        try {
            return new FactId(UUID.fromString(value));
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new InvalidInputException("validation.failed", "Invalid FactId value");
        }
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
