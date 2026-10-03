package dev.aegis.notifications.domain.push;

import java.util.Objects;
import java.util.UUID;

/** Identity of one browser's push subscription. */
public record PushSubscriptionId(UUID value) {
    public PushSubscriptionId {
        Objects.requireNonNull(value, "push subscription id");
    }

    public static PushSubscriptionId random() {
        return new PushSubscriptionId(UUID.randomUUID());
    }

    public static PushSubscriptionId of(String value) {
        try {
            return new PushSubscriptionId(UUID.fromString(value));
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new dev.aegis.shared.InvalidInputException("validation.failed", "Invalid PushSubscriptionId value");
        }
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
