package dev.aegis.contracts.domain;

import dev.aegis.shared.DocumentId;
import dev.aegis.shared.InvalidInputException;
import java.util.Objects;

/**
 * Where a fact comes from: the archived document plus, only when the source actually states it, the page and the
 * quoted passage. Aegis never derives a page number it was not given, so an absent page stays absent.
 */
public record SourceReference(DocumentId documentId, Integer page, String quote) {
    public static final int MAX_QUOTE_LENGTH = 500;

    public SourceReference {
        Objects.requireNonNull(documentId, "document id");
        if (page != null && page < 1) {
            throw new InvalidInputException("contract.source.page.invalid", "Page numbers start at 1");
        }
        if (quote != null) {
            quote = quote.trim();
            if (quote.isEmpty()) {
                quote = null;
            } else if (quote.length() > MAX_QUOTE_LENGTH) {
                throw new InvalidInputException("contract.source.quote.tooLong",
                        "Quote exceeds " + MAX_QUOTE_LENGTH);
            }
        }
    }

    public static SourceReference document(DocumentId documentId) {
        return new SourceReference(documentId, null, null);
    }
}
