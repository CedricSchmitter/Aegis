package dev.aegis.notifications.application.api;

import dev.aegis.shared.Actor;

/** Sending pushes: the recurring delivery of new notices, and a test push the member asks for. */
public interface PushDispatch {
    record Report(int sent, int failed, int expired) {
    }

    /** Result of a test push: SENT (the push service accepted it), FAILED, EXPIRED or UNAVAILABLE (no server key). */
    record TestResult(String outcome) {
    }

    /** Pushes the members' unread notices that no device has received yet. */
    Report dispatchPending();

    /** Sends a test message to one of the member's own devices now. */
    TestResult sendTest(Actor actor, String deviceId);
}
