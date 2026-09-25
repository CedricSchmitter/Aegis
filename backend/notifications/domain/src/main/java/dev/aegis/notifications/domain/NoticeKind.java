package dev.aegis.notifications.domain;

/** Stable notice types; the UI localizes the message from kind and parameters. */
public enum NoticeKind {
    TASK_ASSIGNED,
    TASK_COMPLETED,
    DOCUMENT_ARCHIVED,
    DOCUMENT_ARCHIVAL_FAILED,
    CONTRACT_CANCELLATION_REMINDER,
    REVIEW_ITEM,
    JOB_STALE,
    BACKUP_FAILED,
    DISK_SPACE_LOW
}
