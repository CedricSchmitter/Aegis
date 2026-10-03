package dev.aegis.notifications.application.push;

import java.time.Duration;
import java.util.Objects;

/**
 * What a device shows: title, body and the app path a tap opens. {@code tag} lets a later push replace the shown one,
 * {@code topic} lets the push service replace a message that is still waiting for the device, and {@code ttl} is how
 * long the push service keeps it while the device is offline.
 */
public record PushMessage(String title, String body, String path, String tag, String topic, Duration ttl) {
    public PushMessage {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(tag, "tag");
        Objects.requireNonNull(ttl, "ttl");
        if (!path.startsWith("/") || path.startsWith("//")) {
            throw new IllegalArgumentException("a push opens a path of the app");
        }
    }
}
