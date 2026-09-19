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

    /** Folder placement ({@code categoryId} null = Unsorted) and own label assignments with provenance. */
    public record Classification(UUID categoryId, List<String> categoryPath, List<Assignment> assignments) {
    }

    /** {@code displayName} is a readable name for exports; built-in labels without a custom name use their key. */
    public record Assignment(UUID labelId, String facet, String displayName, String origin, String reviewState,
            Double confidence, String sourceRef, MemberId decidedBy, Instant decidedAt) {
    }
}
