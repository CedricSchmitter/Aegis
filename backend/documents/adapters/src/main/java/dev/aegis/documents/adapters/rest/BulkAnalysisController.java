package dev.aegis.documents.adapters.rest;

import dev.aegis.documents.application.api.BulkAnalysisCommands;
import dev.aegis.documents.application.api.BulkAnalysisCommands.ScopeRequest;
import dev.aegis.documents.application.api.BulkAnalysisPreview;
import dev.aegis.documents.application.api.BulkAnalysisQueries;
import dev.aegis.documents.application.api.BulkAnalysisView;
import dev.aegis.shared.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bulk classification of existing documents that were never classified: preview with cost estimate,
 * explicit start, progress and cancellation. Nothing here runs without a member's start.
 */
@RestController
@RequestMapping("/api/documents/bulk-analyses")
@Tag(name = "documents")
public class BulkAnalysisController {
    private final BulkAnalysisCommands commands;
    private final BulkAnalysisQueries queries;

    public BulkAnalysisController(BulkAnalysisCommands commands, BulkAnalysisQueries queries) {
        this.commands = commands;
        this.queries = queries;
    }

    /** {@code kind}: {@code ALL}, {@code UNSORTED} or {@code FOLDER} with {@code folderId}. */
    public record BulkScopeDto(@NotBlank String kind, @Schema(nullable = true) String folderId,
            boolean includeSubfolders, @Schema(nullable = true) List<String> folderPath) {
        ScopeRequest toRequest() {
            return new ScopeRequest(kind, RestIds.optional(folderId), includeSubfolders);
        }

        static BulkScopeDto from(BulkAnalysisView.Scope scope) {
            return new BulkScopeDto(scope.kind(), scope.folderId() == null ? null : scope.folderId().toString(),
                    scope.includeSubfolders(), scope.folderPath());
        }
    }

    public record BulkStartRequest(@NotNull @Valid BulkScopeDto scope, @PositiveOrZero int expectedDocuments) {
    }

    public record BulkDocumentRefDto(String id, String title) {
        static BulkDocumentRefDto from(BulkAnalysisView.DocumentRef ref) {
            return new BulkDocumentRefDto(ref.id().toString(), ref.title());
        }

        static List<BulkDocumentRefDto> all(List<BulkAnalysisView.DocumentRef> refs) {
            return refs.stream().map(BulkDocumentRefDto::from).toList();
        }
    }

    public record BulkCostDto(BigDecimal perDocument, String basis, int samples, BigDecimal total, BigDecimal maximum,
            BigDecimal dailyLimit, BigDecimal spentToday, BigDecimal remainingToday, int documentsToday,
            @Schema(nullable = true) Integer days, String currency, Instant nextBudgetDay) {
    }

    public record BulkPreviewDto(BulkScopeDto scope, int documents, boolean limited, int maxDocuments,
            List<BulkDocumentRefDto> sample, BulkCostDto cost, @Schema(nullable = true) String refusal,
            @Schema(nullable = true) String runningId) {
        static BulkPreviewDto from(BulkAnalysisPreview preview) {
            BulkAnalysisPreview.Cost c = preview.cost();
            return new BulkPreviewDto(BulkScopeDto.from(preview.scope()), preview.documents(), preview.limited(),
                    preview.maxDocuments(), BulkDocumentRefDto.all(preview.sample()),
                    new BulkCostDto(c.perDocument(), c.basis(), c.samples(), c.total(), c.maximum(), c.dailyLimit(),
                            c.spentToday(), c.remainingToday(), c.documentsToday(), c.days(), c.currency(),
                            c.nextBudgetDay()),
                    preview.refusal(), preview.runningId() == null ? null : preview.runningId().toString());
        }
    }

    public record BulkPlacementGroupDto(String key, List<String> path, List<String> newFolders, int documents,
            BulkDocumentRefDto example) {
    }

    public record BulkReasonGroupDto(String code, int documents, List<BulkDocumentRefDto> examples) {
        static List<BulkReasonGroupDto> all(List<BulkAnalysisView.ReasonGroup> groups) {
            return groups.stream().map(g -> new BulkReasonGroupDto(g.code(), g.documents(),
                    BulkDocumentRefDto.all(g.examples()))).toList();
        }
    }

    /** See {@link BulkAnalysisView} for the meaning of the counts. */
    public record BulkAnalysisDto(String id, String state, @Schema(nullable = true) String stopCode, BulkScopeDto scope,
            String requestedBy, Instant requestedAt, @Schema(nullable = true) String cancelledBy,
            @Schema(nullable = true) Instant cancelledAt, @Schema(nullable = true) Instant finishedAt, int total,
            int pending, int inFlight, int classified, int needsReview, int failed, int notClassified, int skipped,
            int cancelled, int superseded, @Schema(nullable = true) Instant waitingForBudgetUntil,
            List<BulkPlacementGroupDto> placementGroups, List<BulkReasonGroupDto> failures,
            List<BulkReasonGroupDto> skips, List<BulkDocumentRefDto> reviewDocuments) {
        static BulkAnalysisDto from(BulkAnalysisView v) {
            return new BulkAnalysisDto(v.id().toString(), v.state(), v.stopCode(), BulkScopeDto.from(v.scope()),
                    v.requestedBy().toString(), v.requestedAt(),
                    v.cancelledBy() == null ? null : v.cancelledBy().toString(), v.cancelledAt(), v.finishedAt(),
                    v.total(), v.pending(), v.inFlight(), v.classified(), v.needsReview(), v.failed(),
                    v.notClassified(), v.skipped(), v.cancelled(), v.superseded(), v.waitingForBudgetUntil(),
                    v.placementGroups().stream().map(g -> new BulkPlacementGroupDto(g.key(), g.path(), g.newFolders(),
                            g.documents(), BulkDocumentRefDto.from(g.example()))).toList(),
                    BulkReasonGroupDto.all(v.failures()), BulkReasonGroupDto.all(v.skips()),
                    BulkDocumentRefDto.all(v.reviewDocuments()));
        }
    }

    @Operation(operationId = "previewBulkAnalysis",
            summary = "Show which never-classified documents a bulk classification would include and its cost")
    @PostMapping("/preview")
    public BulkPreviewDto preview(Actor actor, @Valid @RequestBody BulkScopeDto scope) {
        return BulkPreviewDto.from(commands.preview(actor, scope.toRequest()));
    }

    @Operation(operationId = "startBulkAnalysis",
            summary = "Start the bulk classification the preview showed; refused when the scope changed")
    @PostMapping
    public BulkAnalysisDto start(Actor actor, @Valid @RequestBody BulkStartRequest body) {
        return BulkAnalysisDto.from(commands.start(actor, body.scope().toRequest(), body.expectedDocuments()));
    }

    @Operation(operationId = "listBulkAnalyses", summary = "The running and the most recent bulk classifications")
    @GetMapping
    public List<BulkAnalysisDto> list(Actor actor) {
        return queries.recent(actor).stream().map(BulkAnalysisDto::from).toList();
    }

    @Operation(operationId = "getBulkAnalysis", summary = "One bulk classification with its progress and results")
    @GetMapping("/{id}")
    public BulkAnalysisDto get(Actor actor, @PathVariable("id") String id) {
        return BulkAnalysisDto.from(queries.get(actor, RestIds.required(id)));
    }

    @Operation(operationId = "cancelBulkAnalysis",
            summary = "Cancel a running bulk classification; results already applied stay")
    @PostMapping("/{id}/cancel")
    public BulkAnalysisDto cancel(Actor actor, @PathVariable("id") String id) {
        return BulkAnalysisDto.from(commands.cancel(actor, RestIds.required(id)));
    }
}
