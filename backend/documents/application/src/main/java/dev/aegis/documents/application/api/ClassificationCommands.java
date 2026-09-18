package dev.aegis.documents.application.api;

import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import java.util.List;
import java.util.UUID;

/**
 * Member edits of the folder and labels of a document. {@code expectedVersion} is the classification version from
 * {@link ClassificationView#version()}.
 */
public interface ClassificationCommands {

    record Placement(DocumentId documentId, long expectedVersion) {
    }

    /** {@code categoryId} null places the document in Unsorted. */
    DocumentSummary place(Actor actor, DocumentId id, UUID categoryId, long expectedVersion);

    /** Moves several documents in one unit of work; any stale version aborts the whole move. */
    int placeAll(Actor actor, List<Placement> placements, UUID categoryId);

    /** Makes the given labels the own effective labels of the document (all facets at once). */
    DocumentSummary setLabels(Actor actor, DocumentId id, List<UUID> labelIds, long expectedVersion);

    DocumentSummary acceptSuggestion(Actor actor, DocumentId id, UUID labelId, long expectedVersion);

    DocumentSummary rejectSuggestion(Actor actor, DocumentId id, UUID labelId, long expectedVersion);
}
