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
}
