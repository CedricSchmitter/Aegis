package dev.aegis.notifications.domain.push;

/**
 * Outcome of the latest attempt to push a notice to one subscription. {@code SENT} means the push service accepted
 * the message; whether the device displayed it is not known, because push services do not confirm that.
 */
public enum PushDeliveryState {
    SENT,
    FAILED,
    /** The push service no longer knew the subscription; the notice stays in the in-app inbox. */
    EXPIRED
}
