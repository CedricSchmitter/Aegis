package dev.aegis.documents.application.api;

import java.util.List;
import java.util.UUID;

/**
 * Folder placement and labels of a document. {@code categoryId} is null for Unsorted. {@code folderSubjects} are
 * subjects inherited from the folder that the document does not already carry. {@code placement} tells where the
 * current placement came from and {@code placementSuggestion} is a folder a run proposed and a member has not
 * decided on yet (null when there is none). {@code version} guards classification edits and is independent of the
 * processing version of the document.
 */
public record ClassificationView(UUID categoryId, List<CategoryRef> path, List<LabelChip> labels,
        List<SubjectView> folderSubjects, PlacementView.Origin placement,
        PlacementView.Suggestion placementSuggestion, long version) {
    public static final ClassificationView UNSORTED = new ClassificationView(null, List.of(), List.of(), List.of(),
            PlacementView.Origin.NONE, null, 0);
}
