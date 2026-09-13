package dev.aegis.documents.application.api;

import dev.aegis.shared.DocumentId;
import java.util.Optional;

/** Public contract used by other contexts to validate and describe a document link. */
public interface DocumentReferenceQuery {
    Optional<DocumentReference> findReference(DocumentId id);
}
