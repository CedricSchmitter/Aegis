package dev.aegis.documents.adapters.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.documents.application.ports.PdfRefusedException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.junit.jupiter.api.Test;

class PdfBoxTextTest {
    private final PdfBoxText text = new PdfBoxText();

    /** One line of text at a height on the page; lines are drawn in the given order. */
    private record Line(String text, float y, boolean invisible) {
        static Line at(String text, float y) {
            return new Line(text, y, false);
        }
    }

    private static byte[] pdf(List<List<Line>> pages) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (List<Line> lines : pages) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    for (Line line : lines) {
                        content.beginText();
                        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        if (line.invisible()) {
                            content.setRenderingMode(RenderingMode.NEITHER);
                        }
                        content.newLineAtOffset(72, line.y());
                        content.showText(line.text());
                        content.endText();
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void everyPageGivesItsOwnTextInReadingOrder() throws IOException {
        byte[] letter = pdf(List.of(
                List.of(Line.at("Stromliefervertrag Nr. 4711", 760), Line.at("Vertragsbeginn: 01.02.2025", 740)),
                List.of(),
                List.of(Line.at("Kündigungsfrist 3 Monate", 700), Line.at("Seite 3", 760)),
                List.of(new Line("Unsichtbare OCR-Schicht", 700, true))));

        assertThat(text.pageTexts(letter, 10, 1_000)).hasValueSatisfying(pages -> assertThat(pages).containsExactly(
                "Stromliefervertrag Nr. 4711\nVertragsbeginn: 01.02.2025",
                "",
                "Seite 3\nKündigungsfrist 3 Monate",
                "Unsichtbare OCR-Schicht"));
    }

    @Test
    void aPdfBeyondThePageOrCharacterBoundIsNotRead() throws IOException {
        byte[] three = pdf(List.of(List.of(Line.at("eins", 700)), List.of(Line.at("zwei", 700)),
                List.of(Line.at("drei", 700))));

        assertThat(text.pageTexts(three, 2, 1_000)).isEmpty();
        assertThat(text.pageTexts(three, 3, 11)).isEmpty();
        assertThat(text.pageTexts(three, 3, 12)).hasValueSatisfying(pages -> assertThat(pages).hasSize(3));
    }

    @Test
    void encryptedAndDamagedFilesAreRefusedWithTheirCode() throws IOException {
        byte[] plain = pdf(List.of(List.of(Line.at("geheim", 700))));
        byte[] locked;
        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(plain)) {
            document.protect(new StandardProtectionPolicy("owner", "user", new AccessPermission()));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            locked = out.toByteArray();
        }
        byte[] damaged = "%PDF-1.7\nnot really".getBytes(StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> text.pageTexts(locked, 10, 1_000)).isInstanceOfSatisfying(
                PdfRefusedException.class, e -> assertThat(e.code()).isEqualTo(PdfRefusedException.ENCRYPTED));
        assertThatThrownBy(() -> text.pageTexts(damaged, 10, 1_000)).isInstanceOfSatisfying(
                PdfRefusedException.class, e -> assertThat(e.code()).isEqualTo(PdfRefusedException.UNREADABLE));
    }
}
