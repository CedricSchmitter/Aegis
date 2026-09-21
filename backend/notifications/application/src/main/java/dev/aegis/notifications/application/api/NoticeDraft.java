package dev.aegis.notifications.application.api;

import dev.aegis.shared.MemberId;
import java.util.Map;

/**
 * Request to publish a notice. {@code kind} is one of TASK_ASSIGNED, TASK_COMPLETED, DOCUMENT_ARCHIVED,
 * DOCUMENT_ARCHIVAL_FAILED, CONTRACT_CANCELLATION_REMINDER or REVIEW_ITEM; {@code dedupKey} collapses repeated
 * deliveries about the same fact regardless of read state.
 */
public record NoticeDraft(MemberId recipient, String kind, String dedupKey, Map<String, String> params,
        String linkType, String linkId) {
}
