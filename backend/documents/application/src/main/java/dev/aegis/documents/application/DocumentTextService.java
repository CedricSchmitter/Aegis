package dev.aegis.documents.application;

import dev.aegis.documents.application.api.ContentUnavailableException;
import dev.aegis.documents.application.api.DocumentTextQuery;
import dev.aegis.documents.application.ports.*;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.DocumentRepository;
import dev.aegis.documents.domain.ProcessingState;
import dev.aegis.shared.*;

/** Authorized original-text access using the same bounded, source-verified extraction as the passage index. */
public class DocumentTextService implements DocumentTextQuery {
    static final int MAX_PAGES = DocumentTextExtractor.MAX_PAGES;
    static final int MAX_CHARACTERS = DocumentTextExtractor.MAX_CHARACTERS;
    static final long CACHED_CHARACTERS = 8_000_000;
    private final DocumentRepository documents;
    private final DocumentTextExtractor extractor;
    private final HouseholdAccess access;

    public DocumentTextService(DocumentRepository documents, PaperlessArchive archive, OriginalStore store,
            PdfText pdf, HouseholdAccess access) {
        this(documents, new DocumentTextExtractor(archive, store, pdf), access);
    }
    public DocumentTextService(DocumentRepository documents, DocumentTextExtractor extractor, HouseholdAccess access) {
        this.documents = documents; this.extractor = extractor; this.access = access;
    }
    @Override public DocumentText text(Actor actor, DocumentId id) {
        access.requireMember(actor);
        Document document = load(id);
        try {
            DocumentTextExtractor.Text text = extractor.verified(document);
            Document current = load(id); // A cache/extraction cannot bypass a concurrent trash or archive rebound.
            if (!current.paperlessDocumentId().equals(document.paperlessDocumentId())
                    || !current.checksum().equals(document.checksum())) {
                throw new DocumentTextExtractor.Refused("text.sourceChanged");
            }
            return new DocumentText(id, current.title(), text.pages(), text.paged());
        } catch (ArchiveUnavailableException | ArchiveRejectedException | DocumentTextExtractor.Refused unavailable) {
            throw new ContentUnavailableException("the archive text of " + id + " is unavailable", unavailable);
        }
    }
    private Document load(DocumentId id) {
        Document document = documents.findById(id).orElseThrow(() -> new NotFoundException("document", id.toString()));
        document.requireNotTrashed();
        if (document.state() != ProcessingState.ARCHIVED || document.paperlessDocumentId().isEmpty()) {
            throw new InvalidInputException("document.notArchived", "The document is not archived yet");
        }
        return document;
    }
}
