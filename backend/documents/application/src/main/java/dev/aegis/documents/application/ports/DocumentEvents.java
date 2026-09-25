package dev.aegis.documents.application.ports;

import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;

/**
 * Outbound notifications about document processing; the composition root routes them to the notice inbox. A successful
 * archival is no event: the upload already confirmed saving and the document shows its state (issue #83).
 */
public interface DocumentEvents {
    void documentArchivalFailed(DocumentId id, String title, MemberId uploadedBy, String failureCode);
}
