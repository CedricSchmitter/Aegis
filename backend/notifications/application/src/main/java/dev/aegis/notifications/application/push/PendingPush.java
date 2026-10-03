package dev.aegis.notifications.application.push;

import dev.aegis.notifications.domain.NoticeId;
import dev.aegis.notifications.domain.push.PushEndpoint;
import dev.aegis.notifications.domain.push.PushSubscriptionId;
import java.util.List;

/** A message ready to be sent to one subscription, covering these notices (none for a test push). */
public record PendingPush(PushSubscriptionId subscription, PushEndpoint endpoint, List<NoticeId> notices,
        PushMessage message) {
    public PendingPush {
        notices = List.copyOf(notices);
    }
}
