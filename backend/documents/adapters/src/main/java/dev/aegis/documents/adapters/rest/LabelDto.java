package dev.aegis.documents.adapters.rest;

import dev.aegis.documents.application.api.LabelView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * REST representation of a vocabulary entry. {@code builtinKey} marks product-owned values that the UI translates
 * through {@code labels.builtin.<key>}; {@code name} is a member-authored name and wins when present.
 */
public record LabelDto(String id, String facet, @Schema(nullable = true) String builtinKey,
        @Schema(nullable = true) String name, List<String> aliases, String status,
        @Schema(nullable = true) String mergedInto, long assignmentCount, long suggestionCount, long folderCount,
        long version) {

    static LabelDto from(LabelView view) {
        return new LabelDto(view.id().toString(), view.facet(), view.builtinKey(), view.name(), view.aliases(),
                view.status(), view.mergedInto() == null ? null : view.mergedInto().toString(),
                view.assignmentCount(), view.suggestionCount(), view.folderCount(), view.version());
    }
}
