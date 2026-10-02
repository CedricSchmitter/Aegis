package dev.aegis.documents.application.api;

import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;

/**
 * The text the archive recognized in a document, for readers that quote the shared collection, such as the household
 * assistant. The same household authorization applies as for the original. Reads the archive; call it outside
 * database transactions.
 */
public interface DocumentTextQuery {
    /**
     * {@code text} is the archive's OCR text, empty when it recognized none; the archive separates pages with form
     * feeds where it knows them.
     */
    record DocumentText(DocumentId id, String title, String text) {
    }

    /**
     * @throws dev.aegis.shared.NotFoundException for an unknown document
     * @throws dev.aegis.shared.TrashedException {@code document.trashed} for a document in the trash
     * @throws dev.aegis.shared.InvalidInputException {@code document.notArchived} before the archive holds it
     * @throws ContentUnavailableException when the archive cannot deliver the text right now
     */
    DocumentText text(Actor actor, DocumentId id);
}
