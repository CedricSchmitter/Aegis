package dev.aegis.notifications.application.push;

import dev.aegis.notifications.domain.push.PushEndpoint;

/** Outbound port to the browser's push service (Web Push: RFC 8030, encrypted per RFC 8291, VAPID per RFC 8292). */
public interface PushSender {
    enum Outcome {
        /** The push service accepted the message; it does not confirm that the device showed it. */
        ACCEPTED,
        /** The push service no longer knows the subscription (404 or 410). */
        GONE,
        /** The push service refused this message or the server's credentials; retrying unchanged may fail again. */
        REJECTED,
        /** The push service could not be reached or asked to retry later. */
        UNAVAILABLE,
        /** The server has no push key; nothing was sent. */
        NOT_CONFIGURED
    }

    record Result(Outcome outcome, Integer statusCode) {
    }

    /** Sends one message. Never throws for a failed delivery; the outcome says what happened. */
    Result send(PushEndpoint endpoint, PushMessage message);
}
