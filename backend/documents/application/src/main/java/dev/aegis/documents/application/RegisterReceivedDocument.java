package dev.aegis.documents.application;

import dev.aegis.documents.domain.Checksum;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.Subject;
import dev.aegis.documents.domain.TitleOrigin;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Transactional step of capture: records the received original, its initial classification and its archival job
 * atomically. The file write happens before, outside the transaction.
 */
public interface RegisterReceivedDocument {

    /**
     * {@code categoryId} null (or a folder that no longer exists) files the document in Unsorted.
     * {@code analyzeWhenArchived} marks an upload for automatic analysis; restores never set it.
     */
    record RegistrationCommand(DocumentId id, String title, TitleOrigin titleOrigin, String originalFilename,
            String contentType, long sizeBytes, Checksum checksum, MemberId uploadedBy, Instant uploadedAt,
            Set<Subject> subjects, String stagingPath, UUID categoryId, boolean analyzeWhenArchived) {
    }

    record RegistrationResult(Document document, boolean duplicate) {
    }

    RegistrationResult register(RegistrationCommand command);
}
