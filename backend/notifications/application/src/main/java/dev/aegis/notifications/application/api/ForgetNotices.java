package dev.aegis.notifications.application.api;

/**
 * Public contract for removing the notices about a record that was deleted permanently (ADR-0021). Called through the
 * composition root inside the transaction that deletes the record, which already checked the member's authorization.
 */
public interface ForgetNotices {
    /**
     * Deletes every member's notices that open this record, read or not. Their texts describe a record that no longer
     * exists, and their links would lead nowhere.
     *
     * @return how many notices were deleted
     */
    int forgetLinkedTo(String linkType, String linkId);
}
