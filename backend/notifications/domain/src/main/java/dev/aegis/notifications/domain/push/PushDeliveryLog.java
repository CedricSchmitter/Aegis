package dev.aegis.notifications.domain.push;

import dev.aegis.notifications.domain.NoticeId;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/** Durable record of push attempts per notice and subscription, so a notice is pushed at most once per device. */
public interface PushDeliveryLog {
    record Attempt(PushDeliveryState state, int attempts, Instant lastAttemptAt) {
    }

    /** The latest attempt for each of these notices on this subscription; notices never attempted are absent. */
    Map<NoticeId, Attempt> attempts(PushSubscriptionId subscription, Collection<NoticeId> notices);

    /** Records one more attempt with this outcome for each notice. */
    void record(PushSubscriptionId subscription, Collection<NoticeId> notices, PushDeliveryState state,
            Integer statusCode, Instant at);
}
