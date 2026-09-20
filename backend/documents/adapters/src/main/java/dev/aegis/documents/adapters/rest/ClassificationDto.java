package dev.aegis.documents.adapters.rest;

import dev.aegis.documents.application.api.ClassificationView;
import dev.aegis.documents.application.api.LabelChip;
import dev.aegis.documents.application.api.PlacementView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * Folder placement and labels of a document. {@code version} guards classification edits and is independent of the
 * document version. A chip with source {@code FOLDER} is inherited from {@code folderId} and has no own review
 * state; {@code OWN} chips carry origin, review state and any reported confidence.
 */
public record ClassificationDto(@Schema(nullable = true) String categoryId, List<CategoryRefDto> path,
        List<LabelChipDto> labels, List<DocumentDto.SubjectDto> folderSubjects, PlacementDto placement,
        @Schema(nullable = true) PlacementSuggestionDto placementSuggestion, long version) {

    public record CategoryRefDto(String id, String name) {
    }

    public record LabelChipDto(String labelId, String facet, @Schema(nullable = true) String builtinKey,
            @Schema(nullable = true) String name, String source, @Schema(nullable = true) String origin,
            @Schema(nullable = true) String reviewState, @Schema(nullable = true) Double confidence,
            @Schema(nullable = true) String sourceRef, @Schema(nullable = true) String folderId) {

        static LabelChipDto from(LabelChip chip) {
            return new LabelChipDto(chip.labelId().toString(), chip.facet(), chip.builtinKey(), chip.name(),
                    chip.source(), chip.origin(), chip.reviewState(), chip.confidence(), chip.sourceRef(),
                    chip.folderId() == null ? null : chip.folderId().toString());
        }
    }

    /**
     * Where the current placement came from. {@code origin} is MANUAL, AUTOMATIC or absent while the document was
     * never filed; {@code revertible} marks an automatic move a member can take back. The confidence is the
     * model's own estimate.
     */
    public record PlacementDto(@Schema(nullable = true) String origin, @Schema(nullable = true) Double confidence,
            @Schema(nullable = true) String sourceRef, @Schema(nullable = true) String by,
            @Schema(nullable = true) Instant at, boolean byMember, boolean revertible) {

        static PlacementDto from(PlacementView.Origin origin) {
            return new PlacementDto(origin.origin(), origin.confidence(), origin.sourceRef(),
                    origin.by() == null ? null : origin.by().toString(), origin.at(), origin.byMember(),
                    origin.revertible());
        }
    }

    /**
     * A folder placement waiting for a member. {@code path} is the full readable path of the target and
     * {@code newFolders} the folders at its end that do not exist yet. {@code impliedLabels} and
     * {@code impliedSubjects} are what the target would add to the document, and {@code otherDocuments} counts
     * further documents with the same pending target.
     */
    public record PlacementSuggestionDto(String key, @Schema(nullable = true) String folderId,
            @Schema(nullable = true) String anchorFolderId, @Schema(nullable = true) Long anchorVersion,
            List<String> path, List<String> newFolders, @Schema(nullable = true) Double confidence,
            @Schema(nullable = true) String evidence, @Schema(nullable = true) String runRef,
            @Schema(nullable = true) String model, @Schema(nullable = true) Instant createdAt,
            List<LabelChipDto> impliedLabels, List<DocumentDto.SubjectDto> impliedSubjects, int otherDocuments) {

        static PlacementSuggestionDto from(PlacementView.Suggestion suggestion) {
            return new PlacementSuggestionDto(suggestion.key(),
                    suggestion.folderId() == null ? null : suggestion.folderId().toString(),
                    suggestion.anchorFolderId() == null ? null : suggestion.anchorFolderId().toString(),
                    suggestion.anchorVersion(), suggestion.path(), suggestion.newFolders(), suggestion.confidence(),
                    suggestion.evidence(), suggestion.runRef(), suggestion.model(), suggestion.createdAt(),
                    suggestion.impliedLabels().stream().map(LabelChipDto::from).toList(),
                    suggestion.impliedSubjects().stream().map(DocumentDto.SubjectDto::from).toList(),
                    suggestion.otherDocuments());
        }
    }

    static ClassificationDto from(ClassificationView view) {
        return new ClassificationDto(view.categoryId() == null ? null : view.categoryId().toString(),
                view.path().stream().map(ref -> new CategoryRefDto(ref.id().toString(), ref.name())).toList(),
                view.labels().stream().map(LabelChipDto::from).toList(),
                view.folderSubjects().stream().map(DocumentDto.SubjectDto::from).toList(),
                PlacementDto.from(view.placement()),
                view.placementSuggestion() == null ? null
                        : PlacementSuggestionDto.from(view.placementSuggestion()),
                view.version());
    }
}
