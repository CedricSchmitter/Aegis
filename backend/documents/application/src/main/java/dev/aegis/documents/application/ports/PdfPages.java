package dev.aegis.documents.application.ports;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Outbound port to a PDF library for the page editor (ADR-0024). A PDF that cannot be used raises
 * {@link PdfRefusedException} with a stable code; other {@link IOException}s are file system trouble.
 */
public interface PdfPages {

    /** The size of a page as it is displayed (its own rotation applied), in points. */
    record PageSize(float width, float height) {
    }

    /**
     * What a source PDF holds. {@code signed} and {@code embeddedFiles} are carried by the source only: pages taken
     * from it lose the signature and the attachments (for example the XML of an e-invoice).
     */
    record Inspection(List<PageSize> pages, boolean signed, boolean embeddedFiles) {
        public Inspection {
            pages = List.copyOf(pages);
        }

        public int pageCount() {
            return pages.size();
        }
    }

    /**
     * One page of a new document: page {@code pageIndex} of the source registered under {@code source}, turned by
     * {@code rotation} degrees on top of its own rotation. {@code fitToA4} shrinks a page whose long side exceeds A4
     * to A4, for photos whose archive rendition is sized from 72 dpi metadata.
     */
    record Placement(String source, int pageIndex, int rotation, boolean fitToA4) {
    }

    /**
     * Opens and checks a source.
     *
     * @throws PdfRefusedException {@code pageEdit.source.encrypted} when a password is needed to open it,
     *         {@code pageEdit.source.unreadable} when it cannot be parsed
     */
    Inspection inspect(Path pdf) throws IOException;

    /** A JPEG of one page with the given length of its long side in pixels. */
    byte[] renderPreview(Path pdf, int pageIndex, int longSidePixels) throws IOException;

    /**
     * Writes a new PDF of the placements to {@code target}. The output is deterministic: the same sources,
     * placements and {@code fileId} always give the same bytes, which is what lets checksum duplicate detection
     * recognize a repeated plan. Encryption, signatures and attachments of the sources are not carried over.
     *
     * @param sources the files the placements refer to, by key
     * @param fileId stable bytes for the PDF file identifier
     */
    void assemble(Map<String, Path> sources, List<Placement> pages, byte[] fileId, Path target) throws IOException;
}
