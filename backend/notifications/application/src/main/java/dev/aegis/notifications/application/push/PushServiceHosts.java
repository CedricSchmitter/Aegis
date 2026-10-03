package dev.aegis.notifications.application.push;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The push service hosts a subscription may point to: a host matches a listed name exactly or as a subdomain. Keeps a
 * subscription from making the server post to an arbitrary or internal address.
 */
public record PushServiceHosts(List<String> names) {
    /** The services of Chrome and Samsung Internet (FCM), Firefox, Safari and Edge. */
    public static final PushServiceHosts DEFAULT = new PushServiceHosts(List.of("fcm.googleapis.com",
            "android.googleapis.com", "push.services.mozilla.com", "push.apple.com", "notify.windows.com"));

    public PushServiceHosts {
        names = names.stream().map(name -> Objects.requireNonNull(name).strip().toLowerCase(Locale.ROOT))
                .filter(name -> !name.isEmpty()).toList();
    }

    public boolean allows(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return names.stream().anyMatch(name -> normalized.equals(name) || normalized.endsWith("." + name));
    }
}
