package dev.aegis.documents.application.api;

import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import java.util.List;

/** Member-initiated changes to a captured document. */
public interface DocumentCommands {
    /** Re-queues archival of a FAILED document; the retained original is resubmitted. */
    DocumentSummary retryArchival(Actor actor, DocumentId id);

    DocumentSummary changeSubjects(Actor actor, DocumentId id, List<SubjectView> subjects, long expectedVersion);

    DocumentSummary rename(Actor actor, DocumentId id, String title, long expectedVersion);

    /**
     * Moves the document to the trash (ADR-0010). Nothing is deleted: the original, archive, metadata and every link
     * stay, and ordinary views hide the document. Repeating it for a trashed document returns it unchanged.
     */
    DocumentSummary moveToTrash(Actor actor, DocumentId id, long expectedVersion);

    /**
     * Brings a trashed document back with its identity and data. An automatic analysis that waited while it was in
     * the trash starts now. Repeating it for a document that is not in the trash returns it unchanged.
     */
    DocumentSummary restoreFromTrash(Actor actor, DocumentId id, long expectedVersion);
}
