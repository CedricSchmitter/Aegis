package dev.aegis.notifications.application.api;

import dev.aegis.shared.Actor;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

/**
 * The member's own push setup: the browsers that receive pushes and what is pushed to them. Every call requires an
 * active member and touches only that member's subscriptions and preferences.
 */
public interface PushSettings {
    /** Whether the server can send pushes, and the public key browsers subscribe with (base64url, VAPID). */
    record PushConfig(boolean available, String publicKey) {
    }

    /** One subscribed browser; {@code state} is ACTIVE, FAILING or EXPIRED. */
    record PushDevice(String id, String deviceLabel, String state, Instant createdAt, Instant lastSentAt) {
    }

    record Subscribe(String endpoint, String p256dh, String auth, String deviceLabel) {
    }

    /** {@code quietStart}/{@code quietEnd} are both null when the member wants no quiet hours. */
    record Preferences(Set<String> categories, LocalTime quietStart, LocalTime quietEnd, boolean showDetails,
            long version) {
    }

    PushConfig config(Actor actor);

    /**
     * Subscribes this browser for the member, or renews its subscription. A browser that was subscribed for the
     * other member now receives the actor's pushes instead.
     */
    PushDevice subscribe(Actor actor, Subscribe subscription);

    List<PushDevice> devices(Actor actor);

    /** Removes one of the member's devices; the browser should also drop its own subscription. */
    void removeDevice(Actor actor, String deviceId);

    Preferences preferences(Actor actor);

    /**
     * @throws dev.aegis.shared.StaleVersionException when the preferences changed meanwhile
     */
    Preferences changePreferences(Actor actor, Preferences preferences);
}
