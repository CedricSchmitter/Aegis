package dev.aegis.portability.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Versioned structured export files. Field names are stable English identifiers; ids are strings so that files stay
 * readable and migratable without Aegis.
 */
public final class ExportFormat {
    public static final int SCHEMA_VERSION = 1;
    public static final String MANIFEST = "manifest.json";
    public static final String DOCUMENTS_JSON = "data/documents.json";
    public static final String TASKS_JSON = "data/tasks.json";
    public static final String MEMBERS_JSON = "data/members.json";
    public static final String DOCUMENTS_CSV = "readable/documents.csv";
    public static final String TASKS_CSV = "readable/tasks.csv";
    public static final String INDEX_MD = "README.md";
    public static final String ORIGINALS_DIR = "documents/originals/";

    private ExportFormat() {
    }

    public record SubjectEntry(String kind, String memberId) {
    }

    public record DocumentEntry(String id, String title, String originalFilename, String contentType, long sizeBytes,
            String checksumSha256, String uploadedBy, Instant uploadedAt, List<SubjectEntry> subjects,
            String processingState, Integer paperlessDocumentId, Instant archivedAt, String originalPath) {
    }

    public record TaskEntry(String id, String title, String note, String ownerId, String status, String dueDate,
            String plannedDate, String sourceDocumentId, String createdBy, Instant createdAt, Instant updatedAt,
            Instant completedAt, String completedBy) {
    }

    public record MemberEntry(String id, String username, String displayName, String locale, boolean active) {
    }

    public record DocumentsFile(int schemaVersion, Instant exportedAt, List<DocumentEntry> documents) {
    }

    public record TasksFile(int schemaVersion, Instant exportedAt, List<TaskEntry> tasks) {
    }

    public record MembersFile(int schemaVersion, Instant exportedAt, List<MemberEntry> members) {
    }

    /** Checksums of every file in the generation plus completeness information. */
    public record Manifest(int schemaVersion, String generation, Instant exportedAt, String producer,
            int documentCount, int taskCount, int memberCount, List<String> missingOriginals,
            Map<String, String> fileChecksumsSha256) {
    }
}
