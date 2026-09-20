package dev.aegis.portability.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Versioned structured export files. Field names are stable English identifiers; ids are strings so that files stay
 * readable and migratable without Aegis. Schema 2 adds the shared catalog (folders and label vocabulary) and the
 * classification of each document; schema 3 adds the title origin, the friendly filename the original is exported
 * under and the automatic analysis (generated values, member decisions, filename override and history); schema 4
 * adds where a folder placement came from and a placement a run proposed that nobody has decided on; schema 5
 * adds the contracts with every observed fact, its provenance, validity period and review state. Older files
 * restore without the newer parts.
 */
public final class ExportFormat {
    public static final int SCHEMA_VERSION = 6;
    public static final Set<Integer> SUPPORTED_SCHEMA_VERSIONS = Set.of(1, 2, 3, 4, 5, 6);
    public static final String MANIFEST = "manifest.json";
    public static final String DOCUMENTS_JSON = "data/documents.json";
    public static final String TASKS_JSON = "data/tasks.json";
    public static final String MEMBERS_JSON = "data/members.json";
    public static final String CATALOG_JSON = "data/catalog.json";
    public static final String CONTRACTS_JSON = "data/contracts.json";
    public static final String DOCUMENTS_CSV = "readable/documents.csv";
    public static final String TASKS_CSV = "readable/tasks.csv";
    public static final String LABELS_CSV = "readable/labels.csv";
    public static final String CONTRACTS_CSV = "readable/contracts.csv";
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

    /**
     * Folder placement ({@code folderId} null = Unsorted) and own labels of a document. {@code placement} and
     * {@code suggestion} are absent before schema 4; a placement without them counts as a member's decision.
     */
    public record ClassificationEntry(String folderId, List<String> folderPath, List<AssignmentEntry> labels,
            PlacementEntry placement, PlacementSuggestionEntry suggestion) {
    }

    /**
     * Where a folder placement came from: {@code MANUAL}, {@code AUTOMATIC} or absent. {@code byMember} marks a
     * document a member placed or moved, which keeps later automatic runs from filing it again.
     */
    public record PlacementEntry(String origin, Double confidence, String sourceRef, String by, Instant at,
            boolean byMember) {
    }

    /**
     * A placement a run proposed and nobody decided on yet: an existing {@code folderId}, or {@code newPath} below
     * {@code anchorFolderId}. {@code path} is the readable target for humans.
     */
    public record PlacementSuggestionEntry(String folderId, String anchorFolderId, List<String> newPath,
            List<String> path, Double confidence, String evidence, String runRef, String model, int generation,
            Instant createdAt) {
    }

    /**
     * {@code originalFilename} is the immutable upload name; {@code filename} the friendly name under which the
     * unchanged original is stored in {@code originalPath}. {@code titleOrigin} and {@code analysis} are absent
     * before schema 3.
     */
    public record DocumentEntry(String id, String title, String titleOrigin, String originalFilename,
            String filename, String contentType, long sizeBytes, String checksumSha256, String uploadedBy,
            Instant uploadedAt, List<SubjectEntry> subjects, String processingState, Integer paperlessDocumentId,
            Instant archivedAt, String originalPath, ClassificationEntry classification, AnalysisEntry analysis) {
    }

    /** A name the classifier found that was not in the vocabulary, with the member's decision. */
    public record CandidateEntry(String facet, String name, String evidence, Double confidence, String decision,
            String labelId, String decidedBy, Instant decidedAt) {
    }

    public record SubjectSuggestionEntry(SubjectEntry subject, Double confidence, String evidence, String decision,
            String decidedBy, Instant decidedAt) {
    }

    public record GeneratedEntry(int generation, String runRef, String model, Instant completedAt, String title,
            String documentDate, String shortTopic) {
    }

    /**
     * Automatic analysis of a document: generated values with provenance ({@code runRef}, {@code model}), review
     * decisions, the filename override and earlier generated values. The document date is the evidenced issue date.
     */
    public record AnalysisEntry(String status, int generation, String trigger, String requestedBy,
            Instant requestedAt, Instant completedAt, String runRef, String model, int appliedLabels,
            int suggestedLabels, String title, String titleDecision, String titleBefore, String documentDate,
            String documentDateEvidence, String documentDateStatus, String shortTopic, String correspondentLabelId,
            List<String> flags, List<CandidateEntry> candidates, List<SubjectSuggestionEntry> subjects,
            String filenameOverride, String filenameBy, Instant filenameAt, List<GeneratedEntry> history) {
    }

    public record TaskEntry(String id, String title, String note, String ownerId, String status, String dueDate,
            String plannedDate, String sourceDocumentId, String createdBy, Instant createdAt, Instant updatedAt,
            Instant completedAt, String completedBy, String contractId, String contractName, String contractEnd,
            String cancellationDeadline, String reminderDate, Long contractVersion) {
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

    /**
     * One observation about a contract with its provenance and review state. {@code value} is the canonical form
     * of {@code valueKind}: an ISO date, an ISO period, an amount with its currency, {@code true}/{@code false}
     * or free text. {@code supersededBy} and {@code corrects} preserve which observation replaced which.
     */
    public record ContractFactEntry(String id, String type, String valueKind, String value, String validFrom,
            String validUntil, String reviewState, String origin, Double confidence, String sourceDocumentId,
            Integer sourcePage, String sourceQuote, String runRef, String observedBy, Instant observedAt,
            String decidedBy, Instant decidedAt, String supersededBy, String corrects, String note) {
    }

    /** A contract with its complete fact history; absent before schema 5. */
    public record ContractEntry(String id, String name, String status, String createdBy, Instant createdAt,
            Instant updatedAt, List<ContractFactEntry> facts) {
    }

    public record ContractsFile(int schemaVersion, Instant exportedAt, List<ContractEntry> contracts) {
    }

    public record CatalogFile(int schemaVersion, Instant exportedAt, List<LabelEntry> labels,
            List<FolderEntry> folders) {
    }

    /** Checksums of every file in the generation plus completeness information. */
    public record Manifest(int schemaVersion, String generation, Instant exportedAt, String producer,
            int documentCount, int taskCount, int memberCount, int contractCount, List<String> missingOriginals,
            Map<String, String> fileChecksumsSha256) {
    }
}
