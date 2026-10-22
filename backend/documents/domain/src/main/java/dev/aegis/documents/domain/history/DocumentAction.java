package dev.aegis.documents.domain.history;

import java.util.EnumSet;
import java.util.Set;

/**
 * What a recorded document change was (ADR-0029). A member's own edits of title, friendly filename, folder and labels
 * can be undone. An automatic analysis is recorded but corrected through its own review controls, which also remember
 * the member's decision; the trash has its own way back.
 */
public enum DocumentAction {
    RENAMED,
    FILENAME_CHANGED,
    MOVED,
    LABELS_CHANGED,
    ANALYZED,
    TRASHED,
    RESTORED,
    UNDONE;

    private static final Set<DocumentAction> UNDOABLE = EnumSet.of(RENAMED, FILENAME_CHANGED, MOVED,
            LABELS_CHANGED);

    public boolean undoable() {
        return UNDOABLE.contains(this);
    }
}
