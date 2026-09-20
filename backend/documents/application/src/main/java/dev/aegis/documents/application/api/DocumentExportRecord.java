package dev.aegis.documents.application.api;

import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Versioned export view of a document record; the original bytes are exported separately. {@code filename} is the
 * friendly name the original is exported under; {@code originalFilename} stays the immutable upload name.
 * {@code analysis} is null when the document was never analyzed and has no filename override.
 */
public record DocumentExportRecord(DocumentId id, String title, String titleOrigin, String originalFilename,
        String filename, String contentType, long sizeBytes, String checksumSha256, MemberId uploadedBy,
        Instant uploadedAt, List<SubjectView> subjects, String processingState, Integer paperlessDocumentId,
        Instant archivedAt, Classification classification, AnalysisRecord analysis) {

    /**
     * Folder placement ({@code categoryId} null = Unsorted) and own label assignments with provenance.
     * {@code placement} says where the placement came from and {@code suggestion} carries a proposal nobody has
     * decided on yet (both since export schema 4).
     */
    public record Classification(UUID categoryId, List<String> categoryPath, List<Assignment> assignments,
            Placement placement, PlacementSuggestion suggestion) {
    }

    /** Provenance of a folder placement; {@code byMember} marks a document a member placed or moved. */
    public record Placement(String origin, Double confidence, String sourceRef, MemberId by, Instant at,
            boolean byMember) {
        public static final Placement NONE = new Placement(null, null, null, null, null, false);
    }

    /** A proposed placement waiting for a member: an existing folder or a path below {@code anchorFolderId}. */
    public record PlacementSuggestion(UUID folderId, UUID anchorFolderId, List<String> newPath, List<String> path,
            Double confidence, String evidence, String runRef, String model, int generation, Instant createdAt) {
    }

    /** {@code displayName} is a readable name for exports; built-in labels without a custom name use their key. */
    public record Assignment(UUID labelId, String facet, String displayName, String origin, String reviewState,
            Double confidence, String sourceRef, MemberId decidedBy, Instant decidedAt) {
    }
}
