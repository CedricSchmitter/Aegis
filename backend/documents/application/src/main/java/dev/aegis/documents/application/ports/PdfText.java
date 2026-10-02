package dev.aegis.documents.application.ports;

import java.util.List;
import java.util.Optional;

/** Outbound port to a PDF library that reads the text layer of a PDF page by page. */
public interface PdfText {

    /**
     * The text of every page in order, empty for a page without text. Empty when the PDF has more than
     * {@code maxPages} pages or more than {@code maxCharacters} characters of text; extraction stops there.
     *
     * @throws PdfRefusedException when the PDF needs a password or cannot be parsed
     */
    Optional<List<String>> pageTexts(byte[] pdf, int maxPages, int maxCharacters);
}
