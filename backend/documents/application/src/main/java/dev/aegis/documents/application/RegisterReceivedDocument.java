package dev.aegis.documents.application;

import dev.aegis.documents.domain.Checksum;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.Subject;
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

    /** {@code categoryId} null (or a folder that no longer exists) files the document in Unsorted. */
    record RegistrationCommand(DocumentId id, String title, String originalFilename, String contentType,
            long sizeBytes, Checksum checksum, MemberId uploadedBy, Instant uploadedAt, Set<Subject> subjects,
            String stagingPath, UUID categoryId) {
    }

    record RegistrationResult(Document document, boolean duplicate) {
    }

    RegistrationResult register(RegistrationCommand command);
}
