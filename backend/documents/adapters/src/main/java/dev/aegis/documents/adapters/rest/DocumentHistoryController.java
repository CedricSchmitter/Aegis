package dev.aegis.documents.adapters.rest;

import dev.aegis.documents.application.api.DocumentHistory;
import dev.aegis.documents.application.api.DocumentHistory.DocumentChangeView;
import dev.aegis.documents.application.api.DocumentHistory.DocumentHistoryPage;
import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.FieldChange;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Recorded changes of a document and the undo of a member's edit (ADR-0029). */
@RestController
@RequestMapping("/api/documents")
@Tag(name = "documents")
public class DocumentHistoryController {
    private final DocumentHistory history;

    public DocumentHistoryController(DocumentHistory history) {
        this.history = history;
    }

    /**
     * A page of a document's history, newest first. Field values are locale-independent ids or the member's own
     * text: {@code labels} is a sorted, comma-separated list of label ids, {@code folder} a folder id (null for
     * Unsorted), {@code filename} the member's filename stem (null for the generated name).
     */
    public record DocumentHistoryDto(String documentId, Instant recordingSince, List<DocumentChangeDto> changes,
            @Schema(nullable = true) Long nextBeforeSequence) {
    }

    /**
     * {@code actorKind}: MEMBER, AUTOMATION or AI_RUN; {@code actorReference} names the automation or AI run.
     * {@code undoable}: an undo is offered now; {@code undoneBy}: the change that already reversed this one.
     */
    public record DocumentChangeDto(String id, long sequence, Instant occurredAt, String actorKind,
            @Schema(nullable = true) String actorMemberId, @Schema(nullable = true) String actorReference,
            String action, List<DocumentFieldChangeDto> changes, @Schema(nullable = true) String undoOf,
            @Schema(nullable = true) String undoneBy, boolean undoable) {
    }

    /** One field: title, filename, folder or labels; null means no value. */
    public record DocumentFieldChangeDto(String field, @Schema(nullable = true) String before,
            @Schema(nullable = true) String after) {
        static DocumentFieldChangeDto from(FieldChange change) {
            return new DocumentFieldChangeDto(change.field(), change.before(), change.after());
        }
    }

    @Operation(operationId = "documentHistory", summary = "Recorded changes of a document, newest first (ADR-0029)")
    @GetMapping("/{id}/history")
    public DocumentHistoryDto history(Actor actor, @PathVariable("id") String id,
            @RequestParam(name = "beforeSequence", required = false) Long beforeSequence,
            @RequestParam(name = "limit", defaultValue = "30") int limit) {
        DocumentHistoryPage page = history.history(actor, DocumentId.of(id), beforeSequence, limit);
        return new DocumentHistoryDto(page.documentId().toString(), page.recordingSince(),
                page.changes().stream().map(DocumentHistoryController::change).toList(), page.nextBeforeSequence());
    }

    @Operation(operationId = "undoDocumentChange", summary = "Undo one recorded change of a document (ADR-0029)",
            description = "Bound to the values the change left: 409 history.undo.changedSince when a later change "
                    + "replaced one of them, history.undo.alreadyUndone, history.undo.notSupported, "
                    + "history.undo.folderGone or history.undo.labelGone.")
    @PostMapping("/{id}/history/{changeId}/undo")
    public DocumentDto undo(Actor actor, @PathVariable("id") String id, @PathVariable("changeId") UUID changeId) {
        return DocumentDto.from(history.undo(actor, DocumentId.of(id), changeId));
    }

    private static DocumentChangeDto change(DocumentChangeView view) {
        return new DocumentChangeDto(view.id().toString(), view.sequence(), view.occurredAt(), view.actorKind(),
                view.actorMember() == null ? null : view.actorMember().toString(), view.actorReference(),
                view.action(), view.changes().stream().map(DocumentFieldChangeDto::from).toList(),
                view.undoOf() == null ? null : view.undoOf().toString(),
                view.undoneBy() == null ? null : view.undoneBy().toString(), view.undoable());
    }
}
