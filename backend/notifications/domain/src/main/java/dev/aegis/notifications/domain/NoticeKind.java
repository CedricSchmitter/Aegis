package dev.aegis.notifications.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Stable notice types; the UI localizes the message from kind and parameters. Each kind has one place in the combined
 * inbox (issue #83): listed as a message, represented by the review card it announces, or retired.
 */
public enum NoticeKind {
    TASK_ASSIGNED(Placement.MESSAGE),
    TASK_COMPLETED(Placement.MESSAGE),
    /** No longer published: the upload already confirms saving. Stored rows are kept but not listed. */
    DOCUMENT_ARCHIVED(Placement.RETIRED),
    DOCUMENT_ARCHIVAL_FAILED(Placement.REVIEW_CARD),
    CONTRACT_CANCELLATION_REMINDER(Placement.MESSAGE),
    REVIEW_ITEM(Placement.REVIEW_CARD),
    JOB_STALE(Placement.MESSAGE),
    BACKUP_FAILED(Placement.MESSAGE),
    DISK_SPACE_LOW(Placement.MESSAGE);

    /**
     * Where a notice appears. A {@code REVIEW_CARD} notice announces a review card that the inbox shows itself, so it
     * is still published (the delivery trigger for other channels) but not listed a second time.
     */
    public enum Placement {
        MESSAGE,
        REVIEW_CARD,
        RETIRED
    }

    private final Placement placement;

    NoticeKind(Placement placement) {
        this.placement = placement;
    }

    public Placement placement() {
        return placement;
    }

    public static Set<NoticeKind> placedAs(Placement placement) {
        Set<NoticeKind> kinds = EnumSet.noneOf(NoticeKind.class);
        for (NoticeKind kind : values()) {
            if (kind.placement == placement) {
                kinds.add(kind);
            }
        }
        return kinds;
    }
}
