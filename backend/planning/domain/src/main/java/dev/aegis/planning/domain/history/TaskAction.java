package dev.aegis.planning.domain.history;

import java.util.EnumSet;
import java.util.Set;

/**
 * What a recorded task change was. Only a member's own edits of a field can be undone; creation, the trash, a
 * restore from an export and automatic contract work are recorded but have their own way back or none (ADR-0030).
 */
public enum TaskAction {
    CREATED,
    EDITED,
    ASSIGNED,
    PLANNED,
    DUE_DATE_CHANGED,
    COMPLETED,
    REOPENED,
    TRASHED,
    RESTORED,
    IMPORTED,
    UNDONE,
    /** Contract-owned fields of a cancellation review followed a corrected contract (ADR-0008). */
    CONTRACT_UPDATED,
    /** An open cancellation review was superseded because its deadline is no longer current. */
    SUPERSEDED,
    /** A superseded cancellation review became open work again. */
    REVIVED,
    /** The contract of a completed cancellation review was deleted permanently (ADR-0021). */
    CONTRACT_DELETED;

    private static final Set<TaskAction> UNDOABLE = EnumSet.of(EDITED, ASSIGNED, PLANNED, DUE_DATE_CHANGED,
            COMPLETED, REOPENED);

    public boolean undoable() {
        return UNDOABLE.contains(this);
    }
}
