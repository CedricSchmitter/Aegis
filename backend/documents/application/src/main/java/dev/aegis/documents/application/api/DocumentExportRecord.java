package dev.aegis.documents.application.api;

import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import java.time.Instant;
import java.util.List;

/** Versioned export view of a document record; the original bytes are exported separately. */
public record DocumentExportRecord(DocumentId id, String title, String originalFilename, String contentType,
        long sizeBytes, String checksumSha256, MemberId uploadedBy, Instant uploadedAt, List<SubjectView> subjects,
        String processingState, Integer paperlessDocumentId, Instant archivedAt) {
}
