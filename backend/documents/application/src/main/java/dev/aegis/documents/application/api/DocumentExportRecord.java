package dev.aegis.documents.application.api;

import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Versioned export view of a document record; the original bytes are exported separately. */
public record DocumentExportRecord(DocumentId id, String title, String originalFilename, String contentType,
        long sizeBytes, String checksumSha256, MemberId uploadedBy, Instant uploadedAt, List<SubjectView> subjects,
        String processingState, Integer paperlessDocumentId, Instant archivedAt, Classification classification) {

    /** Folder placement ({@code categoryId} null = Unsorted) and own label assignments with provenance. */
    public record Classification(UUID categoryId, List<String> categoryPath, List<Assignment> assignments) {
    }

    /** {@code displayName} is a readable name for exports; built-in labels without a custom name use their key. */
    public record Assignment(UUID labelId, String facet, String displayName, String origin, String reviewState,
            Double confidence, String sourceRef, MemberId decidedBy, Instant decidedAt) {
    }
}
