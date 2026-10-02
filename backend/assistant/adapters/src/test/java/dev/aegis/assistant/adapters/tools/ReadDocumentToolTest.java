package dev.aegis.assistant.adapters.tools;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.documents.application.api.DocumentTextQuery.DocumentText;
import dev.aegis.shared.DocumentId;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReadDocumentToolTest {

    private static final DocumentText PAGED = new DocumentText(DocumentId.random(), "Brief",
            List.of("Seite eins", "", "Seite drei\nZeile zwei"), true);

    @Test
    void everyPageGetsAMarkerNumberedFromTheStartOfTheDocument() {
        String content = PAGED.text();

        assertThat(ReadDocumentTool.render(PAGED, content, 0, content.length())).isEqualTo(
                "--- page 1 ---\nSeite eins\n--- page 2 ---\n\n--- page 3 ---\nSeite drei\nZeile zwei");
        int third = content.indexOf("Seite drei");
        assertThat(ReadDocumentTool.render(PAGED, content, third, content.length()))
                .isEqualTo("--- page 3 ---\nSeite drei\nZeile zwei");
        assertThat(ReadDocumentTool.render(PAGED, content, 6, third + 5))
                .isEqualTo("--- page 1 ---\neins\n--- page 2 ---\n\n--- page 3 ---\nSeite");
    }

    @Test
    void aTextWithoutPagesIsShownAsItIs() {
        DocumentText unpaged = DocumentText.unpaged(DocumentId.random(), "Brief", "Kein Umbruch\fhier");
        assertThat(ReadDocumentTool.render(unpaged, unpaged.text(), 0, 12)).isEqualTo("Kein Umbruch");
    }

    @Test
    void aPartEndsAtALineBreakOrBlankNearItsLimit() {
        String line = "x".repeat(ReadDocumentTool.PART - 100) + "\n";
        String content = line + "y".repeat(500);
        assertThat(ReadDocumentTool.end(content, 0)).isEqualTo(line.length());

        String words = "wort ".repeat(ReadDocumentTool.PART);
        int end = ReadDocumentTool.end(words, 0);
        assertThat(end).isLessThanOrEqualTo(ReadDocumentTool.PART);
        assertThat(words.charAt(end - 1)).isEqualTo(' ');

        String unbroken = "z".repeat(ReadDocumentTool.PART * 2);
        assertThat(ReadDocumentTool.end(unbroken, 0)).isEqualTo(ReadDocumentTool.PART);
        assertThat(ReadDocumentTool.end("kurz", 0)).isEqualTo(4);
    }
}
