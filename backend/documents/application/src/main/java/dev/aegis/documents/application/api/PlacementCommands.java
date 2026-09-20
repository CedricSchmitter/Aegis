package dev.aegis.documents.application.api;

import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import java.util.List;
import java.util.UUID;

/**
 * Member decisions about a proposed folder placement (issue #67, ADR-0005). Accepting a proposed path creates the
 * missing folders and moves the document in one transaction; rejecting remembers the decision so later runs do not
 * propose the same target again; reverting takes an automatic move back.
 */
public interface PlacementCommands {

    /**
     * Accepts a pending placement. The member may edit the path and its anchor before accepting, so the command
     * carries the wanted target rather than only a reference to the suggestion.
     *
     * @param anchorFolderId the existing folder the path hangs under, {@code null} for the top level
     * @param newPath the folder names below the anchor; empty when {@code folderId} names an existing folder
     * @param expectedVersion the classification version of the document
     * @param expectedAnchorVersion the version of the anchor folder, {@code null} for the top level
     * @param alsoForOtherDocuments moves every other document whose pending suggestion has the same target
     */
    record AcceptPlacement(UUID folderId, UUID anchorFolderId, List<String> newPath, long expectedVersion,
            Long expectedAnchorVersion, boolean alsoForOtherDocuments) {
        public AcceptPlacement {
            newPath = newPath == null ? List.of() : List.copyOf(newPath);
        }
    }

    /** What an acceptance changed: the target folder, the folders it created and how many documents moved. */
    record AcceptedPlacement(DocumentSummary document, UUID folderId, List<String> path, int createdFolders,
            int movedDocuments) {
    }

    AcceptedPlacement acceptPlacement(Actor actor, DocumentId id, AcceptPlacement command);

    /** Rejects the pending suggestion; the same target is not proposed for this document again. */
    DocumentSummary rejectPlacement(Actor actor, DocumentId id, long expectedVersion);

    /** Moves a document that was filed automatically back to Unsorted and remembers the decision. */
    DocumentSummary revertPlacement(Actor actor, DocumentId id, long expectedVersion);
}
