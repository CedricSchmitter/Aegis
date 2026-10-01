package dev.aegis.review.application.api;

import java.util.List;
import java.util.UUID;

/**
 * Public contract for removing the review cards about a record that was deleted permanently (ADR-0021). Called
 * through the composition root inside the transaction that deletes the record, which already checked the member's
 * authorization.
 */
public interface ForgetReviewCards {
    /**
     * Deletes every card whose source or link is this record, whatever its status: a decided card about a deleted
     * record keeps nothing worth deciding again, and its title would keep the deleted record's name.
     *
     * @return the ids of the deleted cards, so the notices announcing them can be removed as well
     */
    List<UUID> forgetRecord(String kind, String id);
}
