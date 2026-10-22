package dev.aegis.documents.application.api;

import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.FieldChange;
import dev.aegis.shared.MemberId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The recorded history of one document and the undo of a member's edit (ADR-0030). */
public interface DocumentHistory {

    /**
     * One recorded change. {@code actorKind} is {@code MEMBER}, {@code AUTOMATION} or {@code AI_RUN};
     * {@code undoable} says whether an undo is offered now, {@code undoneBy} names the change that already reversed it.
     */
    record DocumentChangeView(UUID id, long sequence, Instant occurredAt, String actorKind, MemberId actorMember,
            String actorReference, String action, List<FieldChange> changes, UUID undoOf, UUID undoneBy,
            boolean undoable) {
    }

    /**
     * A page of history, newest first. {@code recordingSince} is when recording began: changes before it were never
     * recorded. {@code nextBeforeSequence} continues the page; null on the last page.
     */
    record DocumentHistoryPage(DocumentId documentId, Instant recordingSince, List<DocumentChangeView> changes,
            Long nextBeforeSequence) {
    }

    /** Both members read every document's history. */
    DocumentHistoryPage history(Actor actor, DocumentId id, Long beforeSequence, int limit);

    /**
     * Reverses one recorded member edit with the document's ordinary operations and records the undo as a new change.
     * It is bound to the values the change left: refused with {@link dev.aegis.shared.UndoConflictException} when a
     * later change replaced one of them, when the change cannot be undone or was undone already. A later change of
     * another field, or of other labels, does not matter.
     */
    DocumentSummary undo(Actor actor, DocumentId id, UUID changeId);
}
