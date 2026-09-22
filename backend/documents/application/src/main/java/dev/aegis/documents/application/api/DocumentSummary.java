package dev.aegis.documents.application.api;

import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.Trash;
import java.time.Instant;
import java.util.List;

/**
 * Read model of a captured document for the UI and other contexts. {@code titleOrigin} is {@code FILENAME},
 * {@code MANUAL} or {@code AUTOMATIC}; {@code originalFilename} is the immutable upload name, {@code filename} the
 * friendly download/export name and {@code analysis} the automatic classification state. {@code trash} is null
 * unless the document is in the trash (ADR-0010).
 */
public record DocumentSummary(DocumentId id, String title, String titleOrigin, String originalFilename,
        String contentType, long sizeBytes, String checksumSha256, MemberId uploadedBy, Instant uploadedAt,
        List<SubjectView> subjects, ProcessingInfo processing, boolean originalAvailable, long version,
        ClassificationView classification, FilenameView filename, AnalysisView analysis, Trash trash) {

    public boolean trashed() {
        return trash != null;
    }
}
