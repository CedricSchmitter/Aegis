package dev.aegis.documents.application.api;

import java.util.List;
import java.util.UUID;

/**
 * A vocabulary entry with usage counts. {@code builtinKey} identifies product-owned values that the UI translates;
 * {@code name} is a member-authored name (shown instead of the translation when present). {@code facet} and
 * {@code status} carry stable English enum names. {@code assignmentCount} counts documents with an own effective
 * assignment, {@code suggestionCount} pending suggestions and {@code folderCount} folders that imply the label.
 */
public record LabelView(UUID id, String facet, String builtinKey, String name, List<String> aliases, String status,
        UUID mergedInto, long assignmentCount, long suggestionCount, long folderCount, long version) {
}
