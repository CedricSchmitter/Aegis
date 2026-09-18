package dev.aegis.assistant.domain;

import dev.aegis.shared.InvalidInputException;
import java.util.Objects;
import java.util.UUID;

/** Stable identity of one bounded AI run; used as job id, audit reference and idempotency anchor. */
public record RunId(UUID value) {
    public RunId {
        Objects.requireNonNull(value, "run id");
    }

    public static RunId random() {
        return new RunId(UUID.randomUUID());
    }

    public static RunId of(String value) {
        try {
            return new RunId(UUID.fromString(value));
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new InvalidInputException("validation.failed", "Invalid RunId value");
        }
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
