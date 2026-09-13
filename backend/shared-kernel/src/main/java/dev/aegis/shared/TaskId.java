package dev.aegis.shared;

import java.util.Objects;
import java.util.UUID;

/** Stable identity of a planning task; referenced by notifications and exports. */
public record TaskId(UUID value) {
    public TaskId {
        Objects.requireNonNull(value, "task id");
    }

    public static TaskId random() {
        return new TaskId(UUID.randomUUID());
    }

    public static TaskId of(String value) {
        try {
            return new TaskId(UUID.fromString(value));
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new dev.aegis.shared.InvalidInputException("validation.failed", "Invalid TaskId value");
        }
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
