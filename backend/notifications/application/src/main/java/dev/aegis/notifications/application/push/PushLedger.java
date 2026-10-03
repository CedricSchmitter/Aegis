package dev.aegis.notifications.application.push;

import dev.aegis.shared.Actor;
import java.util.List;

/**
 * The transactional steps around sending: deciding what to push and recording what happened. Sending itself happens
 * between them, outside any database transaction.
 */
public interface PushLedger {
    /** One message per subscription that has unread notices to push now. */
    List<PendingPush> pending();

    /**
     * A test message for one of the member's own devices.
     *
     * @throws dev.aegis.shared.NotFoundException when the member has no such device
     */
    PendingPush testPush(Actor actor, String deviceId);

    void recordOutcome(PendingPush push, PushSender.Result result);
}
