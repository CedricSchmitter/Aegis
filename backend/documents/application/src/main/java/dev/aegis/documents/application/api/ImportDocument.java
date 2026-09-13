package dev.aegis.documents.application.api;

import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;

/**
 * Restores a document from an export into this instance, keeping the stable Aegis id and re-archiving the original so
 * that a new external Paperless id may be assigned without breaking source links.
 */
public interface ImportDocument {

    record ImportDocumentCommand(DocumentId id, String title, String originalFilename, String contentType,
            MemberId uploadedBy, Instant uploadedAt, List<SubjectView> subjects, String expectedChecksumSha256,
            InputStream content) {
    }

    enum ImportOutcome {
        IMPORTED,
        ALREADY_PRESENT
    }

    record ImportResult(DocumentId id, ImportOutcome outcome) {
    }

    ImportResult importDocument(Actor actor, ImportDocumentCommand command);
}
