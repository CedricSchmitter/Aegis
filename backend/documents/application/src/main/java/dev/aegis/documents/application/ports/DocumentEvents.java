package dev.aegis.documents.application.ports;

import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;

/** Outbound notifications about document processing; the composition root routes them to the notice inbox. */
public interface DocumentEvents {
    void documentArchived(DocumentId id, String title, MemberId uploadedBy);

    void documentArchivalFailed(DocumentId id, String title, MemberId uploadedBy, String failureCode);
}
