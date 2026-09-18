package dev.aegis.documents.application.api;

import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import java.time.Instant;
import java.util.List;

/** Read model of a captured document for the UI and other contexts. */
public record DocumentSummary(DocumentId id, String title, String originalFilename, String contentType,
        long sizeBytes, String checksumSha256, MemberId uploadedBy, Instant uploadedAt, List<SubjectView> subjects,
        ProcessingInfo processing, boolean originalAvailable, long version, ClassificationView classification) {
}
