package dev.aegis.shared;

import java.util.Objects;
import java.util.UUID;

/** Identity of a household member. Shared because documents, tasks and notices reference members. */
public record MemberId(UUID value) {
    public MemberId {
        Objects.requireNonNull(value, "member id");
    }

    public static MemberId random() {
        return new MemberId(UUID.randomUUID());
    }

    public static MemberId of(String value) {
        return new MemberId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
