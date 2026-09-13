package dev.aegis.notifications.domain;

import java.util.Objects;
import java.util.UUID;

/** Identity of a notice in the in-app inbox. */
public record NoticeId(UUID value) {
    public NoticeId {
        Objects.requireNonNull(value, "notice id");
    }

    public static NoticeId random() {
        return new NoticeId(UUID.randomUUID());
    }

    public static NoticeId of(String value) {
        try {
            return new NoticeId(UUID.fromString(value));
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new dev.aegis.shared.InvalidInputException("validation.failed", "Invalid NoticeId value");
        }
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
