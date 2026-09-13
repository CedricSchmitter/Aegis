package dev.aegis.documents.application;

import dev.aegis.documents.domain.Checksum;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.Subject;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import java.time.Instant;
import java.util.Set;

/**
 * Transactional step of capture: records the received original and schedules its archival atomically. The file
 * write happens before, outside the transaction.
 */
public interface RegisterReceivedDocument {

    record RegistrationCommand(DocumentId id, String title, String originalFilename, String contentType,
            long sizeBytes, Checksum checksum, MemberId uploadedBy, Instant uploadedAt, Set<Subject> subjects,
            String stagingPath) {
    }

    record RegistrationResult(Document document, boolean duplicate) {
    }

    RegistrationResult register(RegistrationCommand command);
}
