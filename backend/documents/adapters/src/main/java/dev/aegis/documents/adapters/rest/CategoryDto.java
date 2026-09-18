package dev.aegis.documents.adapters.rest;

import dev.aegis.documents.application.api.CategoryView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/** REST representation of a folder including its explicit label and subject mappings. */
public record CategoryDto(String id, @Schema(nullable = true) String parentId, String name,
        List<String> impliedLabelIds, List<DocumentDto.SubjectDto> impliedSubjects, long documentCount,
        long version) {

    static CategoryDto from(CategoryView view) {
        return new CategoryDto(view.id().toString(), view.parentId() == null ? null : view.parentId().toString(),
                view.name(), view.impliedLabelIds().stream().map(UUID::toString).toList(),
                view.impliedSubjects().stream().map(DocumentDto.SubjectDto::from).toList(), view.documentCount(),
                view.version());
    }
}
