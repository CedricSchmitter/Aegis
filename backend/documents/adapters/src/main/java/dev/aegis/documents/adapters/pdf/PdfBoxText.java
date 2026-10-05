package dev.aegis.documents.adapters.pdf;

import dev.aegis.documents.application.ports.PdfRefusedException;
import dev.aegis.documents.application.ports.PdfText;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/**
 * Reads the text layer of a PDF page by page with Apache PDFBox, such as the one OCR adds to the archive's searchable
 * PDF. Text is ordered by its position on the page, like the archive's own extraction, so quotes taken from either
 * read the same.
 */
@Component
public class PdfBoxText implements PdfText {

    @Override
    public Optional<List<String>> pageTexts(byte[] pdf, int maxPages, int maxCharacters) {
        try (PDDocument document = open(pdf)) {
            int pageCount = document.getNumberOfPages();
            if (pageCount > maxPages) {
                return Optional.empty();
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setLineSeparator("\n");
            stripper.setPageStart("");
            stripper.setPageEnd("");
            List<String> pages = new ArrayList<>(pageCount);
            long characters = 0;
            for (int page = 1; page <= pageCount; page++) {
                if (Thread.currentThread().isInterrupted()) { throw new dev.aegis.documents.application.DocumentTextExtractor.Refused("text.interrupted"); }
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                BoundedText output = new BoundedText(maxCharacters - (int) characters);
                try { stripper.writeText(document, output); }
                catch (TextLimit exceeded) { return Optional.empty(); }
                String text = clean(output.value());
                characters += text.length();
                if (characters > maxCharacters) {
                    return Optional.empty();
                }
                pages.add(text);
            }
            return Optional.of(pages);
        } catch (IOException damaged) {
            throw new PdfRefusedException(PdfRefusedException.UNREADABLE, "the PDF text cannot be read: "
                    + damaged.getMessage());
        }
    }

    private static final class TextLimit extends IOException { }

    /** Stop the stripper's output before a huge single page can allocate an unbounded StringWriter. */
    private static final class BoundedText extends java.io.Writer {
        private final StringBuilder text = new StringBuilder();
        private final int limit;
        private BoundedText(int limit) { this.limit = limit; }
        @Override public void write(char[] value, int offset, int length) throws IOException {
            if (Thread.currentThread().isInterrupted()) {
                throw new dev.aegis.documents.application.DocumentTextExtractor.Refused("text.interrupted");
            }
            if (text.length() + (long) length > limit) { throw new TextLimit(); }
            text.append(value, offset, length);
        }
        @Override public void flush() { }
        @Override public void close() { }
        private String value() { return text.toString(); }
    }

    private static PDDocument open(byte[] pdf) throws IOException {
        try {
            return Loader.loadPDF(pdf, "", null, null, IOUtils.createTempFileOnlyStreamCache());
        } catch (InvalidPasswordException encrypted) {
            throw new PdfRefusedException(PdfRefusedException.ENCRYPTED, "the PDF needs a password");
        }
    }

    /** Page breaks are expressed by the page list, so a form feed inside a page would only mislead. */
    private static String clean(String text) {
        return text.replace("\r", "").replace('\f', '\n').strip();
    }
}
