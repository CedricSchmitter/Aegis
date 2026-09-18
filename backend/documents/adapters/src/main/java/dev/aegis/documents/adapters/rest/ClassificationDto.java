package dev.aegis.documents.adapters.rest;

import dev.aegis.documents.application.api.ClassificationView;
import dev.aegis.documents.application.api.LabelChip;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Folder placement and labels of a document. {@code version} guards classification edits and is independent of the
 * document version. A chip with source {@code FOLDER} is inherited from {@code folderId} and has no own review
 * state; {@code OWN} chips carry origin, review state and any reported confidence.
 */
public record ClassificationDto(@Schema(nullable = true) String categoryId, List<CategoryRefDto> path,
        List<LabelChipDto> labels, List<DocumentDto.SubjectDto> folderSubjects, long version) {

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

    static ClassificationDto from(ClassificationView view) {
        return new ClassificationDto(view.categoryId() == null ? null : view.categoryId().toString(),
                view.path().stream().map(ref -> new CategoryRefDto(ref.id().toString(), ref.name())).toList(),
                view.labels().stream().map(LabelChipDto::from).toList(),
                view.folderSubjects().stream().map(DocumentDto.SubjectDto::from).toList(), view.version());
    }
}
