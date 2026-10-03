package dev.aegis.notifications.domain.push;

import dev.aegis.notifications.domain.NoticeKind;
import java.util.Optional;

/**
 * Groups of notices a member can switch on or off for push delivery. The in-app inbox is not affected: it always lists
 * every message.
 */
public enum PushCategory {
    /** A task was assigned to the member or the member's task was completed. */
    TASKS,
    /** A confirmed contract deadline approaches. */
    DEADLINES,
    /** A review card waits in the inbox. */
    REVIEW,
    /** A job, the backup or the disk needs attention. */
    SYSTEM;

    /** The category of a notice kind; empty for a retired kind, which is never pushed. */
    public static Optional<PushCategory> of(NoticeKind kind) {
        return switch (kind) {
            case TASK_ASSIGNED, TASK_COMPLETED -> Optional.of(TASKS);
            case CONTRACT_CANCELLATION_REMINDER -> Optional.of(DEADLINES);
            case REVIEW_ITEM, DOCUMENT_ARCHIVAL_FAILED -> Optional.of(REVIEW);
            case JOB_STALE, BACKUP_FAILED, DISK_SPACE_LOW -> Optional.of(SYSTEM);
            case DOCUMENT_ARCHIVED -> Optional.empty();
        };
    }
}
