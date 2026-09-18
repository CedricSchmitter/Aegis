package dev.aegis.documents.application.api;

import java.util.List;
import java.util.UUID;

/**
 * Folder placement and labels of a document. {@code categoryId} is null for Unsorted. {@code folderSubjects} are
 * subjects inherited from the folder that the document does not already carry. {@code version} guards
 * classification edits and is independent of the processing version of the document.
 */
public record ClassificationView(UUID categoryId, List<CategoryRef> path, List<LabelChip> labels,
        List<SubjectView> folderSubjects, long version) {
    public static final ClassificationView UNSORTED = new ClassificationView(null, List.of(), List.of(), List.of(), 0);
}
