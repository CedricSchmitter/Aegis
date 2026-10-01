package dev.aegis.documents.application.api;

import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import java.util.List;
import java.util.Optional;

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

    /**
     * Deletes a trashed document permanently (ADR-0021): a background job removes the archive original including the
     * archive's own trash entry, the staging copy and the record. Until the job is done the document stays in the
     * trash as being deleted and can no longer be restored. After a failed attempt the same call starts it again.
     * Repeating it while it is pending returns the document unchanged; once it is gone, the result is empty.
     */
    Optional<DocumentSummary> purge(Actor actor, DocumentId id, long expectedVersion);
}
