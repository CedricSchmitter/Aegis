package dev.aegis.notifications.application.api;

import dev.aegis.shared.MemberId;
import java.util.Map;

/**
 * Request to publish a notice. {@code kind} is one of TASK_ASSIGNED, TASK_COMPLETED, DOCUMENT_ARCHIVAL_FAILED,
 * CONTRACT_CANCELLATION_REMINDER, REVIEW_ITEM, JOB_STALE, BACKUP_FAILED or DISK_SPACE_LOW; the retired
 * DOCUMENT_ARCHIVED is rejected. {@code dedupKey} collapses repeated deliveries about the same fact regardless of read
 * state.
 */
public record NoticeDraft(MemberId recipient, String kind, String dedupKey, Map<String, String> params,
        String linkType, String linkId) {
}
