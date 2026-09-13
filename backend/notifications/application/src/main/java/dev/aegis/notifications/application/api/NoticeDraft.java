package dev.aegis.notifications.application.api;

import dev.aegis.shared.MemberId;
import java.util.Map;

/**
 * Request to publish a notice. {@code kind} is one of TASK_ASSIGNED, TASK_COMPLETED, DOCUMENT_ARCHIVED,
 * DOCUMENT_ARCHIVAL_FAILED; {@code dedupKey} collapses repeated unread notices about the same fact.
 */
public record NoticeDraft(MemberId recipient, String kind, String dedupKey, Map<String, String> params,
        String linkType, String linkId) {
}
