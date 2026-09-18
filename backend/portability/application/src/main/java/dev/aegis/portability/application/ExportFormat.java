package dev.aegis.portability.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Versioned structured export files. Field names are stable English identifiers; ids are strings so that files stay
 * readable and migratable without Aegis. Schema 2 adds the shared catalog (folders and label vocabulary) and the
 * classification of each document; schema 1 files restore without them.
 */
public final class ExportFormat {
    public static final int SCHEMA_VERSION = 2;
    public static final Set<Integer> SUPPORTED_SCHEMA_VERSIONS = Set.of(1, 2);
    public static final String MANIFEST = "manifest.json";
    public static final String DOCUMENTS_JSON = "data/documents.json";
    public static final String TASKS_JSON = "data/tasks.json";
    public static final String MEMBERS_JSON = "data/members.json";
    public static final String CATALOG_JSON = "data/catalog.json";
    public static final String DOCUMENTS_CSV = "readable/documents.csv";
    public static final String TASKS_CSV = "readable/tasks.csv";
    public static final String LABELS_CSV = "readable/labels.csv";
    public static final String INDEX_MD = "README.md";
    public static final String ORIGINALS_DIR = "documents/originals/";

    private ExportFormat() {
    }

    public record SubjectEntry(String kind, String memberId) {
    }

    /** One label on one document with its provenance; {@code label} is a readable name for humans. */
    public record AssignmentEntry(String labelId, String facet, String label, String origin, String reviewState,
            Double confidence, String sourceRef, String decidedBy, Instant decidedAt) {
    }

    /** Folder placement ({@code folderId} null = Unsorted) and own labels of a document. */
    public record ClassificationEntry(String folderId, List<String> folderPath, List<AssignmentEntry> labels) {
    }

    public record DocumentEntry(String id, String title, String originalFilename, String contentType, long sizeBytes,
            String checksumSha256, String uploadedBy, Instant uploadedAt, List<SubjectEntry> subjects,
            String processingState, Integer paperlessDocumentId, Instant archivedAt, String originalPath,
            ClassificationEntry classification) {
    }

    public record TaskEntry(String id, String title, String note, String ownerId, String status, String dueDate,
            String plannedDate, String sourceDocumentId, String createdBy, Instant createdAt, Instant updatedAt,
            Instant completedAt, String completedBy) {
    }

    public record MemberEntry(String id, String username, String displayName, String locale, boolean active) {
    }

    /** {@code label} is the readable name; {@code builtinKey} identifies product-owned values across instances. */
    public record LabelEntry(String id, String facet, String builtinKey, String name, String label,
            List<String> aliases, String status, String mergedInto) {
    }

    /** Folders are listed parents first so a restore can rebuild the tree in order. */
    public record FolderEntry(String id, String parentId, String name, List<String> path,
            List<String> impliedLabelIds, List<SubjectEntry> impliedSubjects) {
    }

    public record DocumentsFile(int schemaVersion, Instant exportedAt, List<DocumentEntry> documents) {
    }

    public record TasksFile(int schemaVersion, Instant exportedAt, List<TaskEntry> tasks) {
    }

    public record MembersFile(int schemaVersion, Instant exportedAt, List<MemberEntry> members) {
    }

    public record CatalogFile(int schemaVersion, Instant exportedAt, List<LabelEntry> labels,
            List<FolderEntry> folders) {
    }

    /** Checksums of every file in the generation plus completeness information. */
    public record Manifest(int schemaVersion, String generation, Instant exportedAt, String producer,
            int documentCount, int taskCount, int memberCount, List<String> missingOriginals,
            Map<String, String> fileChecksumsSha256) {
    }
}
