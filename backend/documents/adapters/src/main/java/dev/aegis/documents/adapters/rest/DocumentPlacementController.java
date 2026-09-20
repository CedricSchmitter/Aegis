package dev.aegis.documents.adapters.rest;

import dev.aegis.documents.application.api.PlacementCommands;
import dev.aegis.documents.application.api.PlacementCommands.AcceptPlacement;
import dev.aegis.documents.application.api.PlacementCommands.AcceptedPlacement;
import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Member decisions about a proposed folder placement (issue #67). Accepting creates the missing folders and moves
 * the document in one transaction; the member may edit the path and its anchor first. Rejecting remembers the
 * decision, and an automatic move can be reverted.
 */
@RestController
@RequestMapping("/api/documents")
@Tag(name = "documents")
public class DocumentPlacementController {
    private final PlacementCommands placements;

    public DocumentPlacementController(PlacementCommands placements) {
        this.placements = placements;
    }

    /**
     * {@code folderId} accepts an existing folder; otherwise {@code newPath} names the folders below
     * {@code anchorFolderId} ({@code null} = top level). {@code expectedAnchorVersion} guards against a concurrent
     * tree edit, {@code expectedVersion} against a concurrent edit of the document.
     */
    public record AcceptPlacementRequest(@Schema(nullable = true) String folderId,
            @Schema(nullable = true) String anchorFolderId, List<String> newPath, long expectedVersion,
            @Schema(nullable = true) Long expectedAnchorVersion, boolean alsoForOtherDocuments) {
    }

    public record AcceptPlacementResponse(DocumentDto document, String folderId, List<String> path,
            int createdFolders, int movedDocuments) {
    }

    public record PlacementDecisionRequest(long expectedVersion) {
    }

    @Operation(operationId = "acceptPlacement",
            summary = "File the document into the proposed folder, creating the missing folders of a proposed path")
    @PostMapping("/{id}/classification/placement/accept")
    public AcceptPlacementResponse accept(Actor actor, @PathVariable("id") String id,
            @Valid @RequestBody AcceptPlacementRequest body) {
        AcceptedPlacement accepted = placements.acceptPlacement(actor, DocumentId.of(id),
                new AcceptPlacement(RestIds.optional(body.folderId()), RestIds.optional(body.anchorFolderId()),
                        body.newPath(), body.expectedVersion(), body.expectedAnchorVersion(),
                        body.alsoForOtherDocuments()));
        return new AcceptPlacementResponse(DocumentDto.from(accepted.document()), accepted.folderId().toString(),
                accepted.path(), accepted.createdFolders(), accepted.movedDocuments());
    }

    @Operation(operationId = "rejectPlacement",
            summary = "Reject the proposed folder; it is not proposed for this document again")
    @PostMapping("/{id}/classification/placement/reject")
    public DocumentDto reject(Actor actor, @PathVariable("id") String id,
            @Valid @RequestBody PlacementDecisionRequest body) {
        return DocumentDto.from(placements.rejectPlacement(actor, DocumentId.of(id), body.expectedVersion()));
    }

    @Operation(operationId = "revertPlacement", summary = "Move an automatically filed document back to Unsorted")
    @PostMapping("/{id}/classification/placement/revert")
    public DocumentDto revert(Actor actor, @PathVariable("id") String id,
            @Valid @RequestBody PlacementDecisionRequest body) {
        return DocumentDto.from(placements.revertPlacement(actor, DocumentId.of(id), body.expectedVersion()));
    }
}
