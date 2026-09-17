package dev.aegis.documents.application.api;

import java.util.List;
import java.util.UUID;

/** Export view of the label vocabulary (including retired entries and merge redirects) and the folder tree. */
public record CatalogExport(List<LabelEntry> labels, List<FolderEntry> folders) {

    public record LabelEntry(UUID id, String facet, String builtinKey, String name, String displayName,
            List<String> aliases, String status, UUID mergedInto) {
    }

    /** Folders are listed parents first; {@code path} holds the readable names from the top level down. */
    public record FolderEntry(UUID id, UUID parentId, String name, List<String> path, List<UUID> impliedLabelIds,
            List<SubjectView> impliedSubjects) {
    }
}
