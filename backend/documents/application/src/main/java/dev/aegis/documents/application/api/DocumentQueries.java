package dev.aegis.documents.application.api;

import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;

/** Shared document collection: both members read the same records. */
public interface DocumentQueries {
    DocumentSummary get(Actor actor, DocumentId id);

    /** Blank query lists everything; otherwise title matches plus archive full-text matches when reachable. */
    DocumentSearchResult search(Actor actor, String query);

    /**
     * Original bytes, preferring the archived copy and falling back to the retained staging copy.
     *
     * @throws OriginalUnavailableException when neither source can serve the file right now
     */
    OriginalContent original(Actor actor, DocumentId id);
}
