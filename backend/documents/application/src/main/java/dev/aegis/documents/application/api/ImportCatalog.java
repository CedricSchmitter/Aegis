package dev.aegis.documents.application.api;

import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Restores the shared catalog and document classifications from an export. Existing local entries always win:
 * equal ids are kept, equal label names or sibling folder names are mapped to the local entry, and a classification
 * is applied only to a document that is still unclassified. Running twice changes nothing.
 */
public interface ImportCatalog {

    record LabelImport(UUID id, String facet, String builtinKey, String name, List<String> aliases, String status,
            UUID mergedInto) {
    }

    /** Folders must be listed parents first. */
    record FolderImport(UUID id, UUID parentId, String name, List<UUID> impliedLabelIds,
            List<SubjectView> impliedSubjects) {
    }

    /** Exported id to local id for labels and folders, plus what was created and what needs attention. */
    record CatalogImportResult(Map<UUID, UUID> labelIds, Map<UUID, UUID> folderIds, int labelsCreated,
            int foldersCreated, List<String> warnings) {
    }

    record AssignmentImport(UUID labelId, String origin, String reviewState, Double confidence, String sourceRef,
            MemberId decidedBy, Instant decidedAt) {
    }

    enum ClassificationOutcome {
        APPLIED,
        KEPT_EXISTING,
        NOTHING_TO_APPLY
    }

    CatalogImportResult importCatalog(Actor actor, List<LabelImport> labels, List<FolderImport> folders);

    /** Ids in the command are local ids (already mapped through {@link CatalogImportResult}). */
    ClassificationOutcome importClassification(Actor actor, DocumentId documentId, UUID categoryId,
            List<AssignmentImport> assignments);
}
