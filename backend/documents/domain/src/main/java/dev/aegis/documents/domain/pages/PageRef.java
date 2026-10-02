package dev.aegis.documents.domain.pages;

import dev.aegis.shared.DocumentId;
import dev.aegis.shared.InvalidInputException;
import java.util.Optional;

/**
 * One page of an edit plan (ADR-0024): a page of a stored document, or of the new pages scanned for the request, turned
 * by a quarter-turn rotation on top of its own. {@code pageIndex} is zero-based.
 */
public record PageRef(DocumentId document, int pageIndex, int rotation) {

    public PageRef {
        if (pageIndex < 0) {
            throw new InvalidInputException("pageEdit.page.invalid", "Page index must not be negative");
        }
        if (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270) {
            throw new InvalidInputException("pageEdit.rotation.invalid", "Rotation must be 0, 90, 180 or 270");
        }
    }

    public static PageRef ofDocument(DocumentId document, int pageIndex, int rotation) {
        if (document == null) {
            throw new InvalidInputException("pageEdit.source.missing", "A document page needs its document");
        }
        return new PageRef(document, pageIndex, rotation);
    }

    public static PageRef ofScan(int pageIndex, int rotation) {
        return new PageRef(null, pageIndex, rotation);
    }

    public boolean fromScan() {
        return document == null;
    }

    public Optional<DocumentId> source() {
        return Optional.ofNullable(document);
    }
}
