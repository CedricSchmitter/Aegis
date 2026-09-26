package dev.aegis.documents.application.api;

import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import java.util.List;

/** Shared document collection: both members read the same records. */
public interface DocumentQueries {
    DocumentSummary get(Actor actor, DocumentId id);

    /** Blank query lists everything; otherwise title matches plus archive full-text matches when reachable. */
    default DocumentSearchResult search(Actor actor, String query) {
        return search(actor, DocumentSearchRequest.text(query));
    }

    /** Documents in the trash, most recently trashed first; ordinary search never returns them. */
    List<DocumentSummary> trashed(Actor actor);

    /**
     * Text, folder and facet search with counts and pagination, evaluated in the database. Also open to household
     * processes such as the review reconciler (ADR-0009); every other query requires a member.
     */
    DocumentSearchResult search(Actor actor, DocumentSearchRequest request);

    /**
     * Original bytes, preferring the archived copy and falling back to the retained staging copy.
     *
     * @throws OriginalUnavailableException when neither source can serve the file right now
     */
    OriginalContent original(Actor actor, DocumentId id);

    /**
     * The searchable PDF the archive generated from the original, served from the archive and named like the
     * document. It exists once the document is archived and the archive keeps an archive version, which it does for
     * every photo.
     *
     * @throws dev.aegis.shared.NotFoundException with resource {@code archivePdf} when there is none (yet)
     */
    ArchivePdf archivePdf(Actor actor, DocumentId id);

    /**
     * The archive's image of the first page, for previews in the list and on phones. It exists once the document is
     * archived; until then the web app shows a placeholder.
     *
     * @throws dev.aegis.shared.NotFoundException with resource {@code thumbnail} while the document is not archived
     */
    DocumentThumbnail thumbnail(Actor actor, DocumentId id);
}
