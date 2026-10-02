package dev.aegis.assistant.adapters.tools;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReadDocumentToolTest {

    @Test
    void pageBreaksBecomeMarkersNumberedFromTheStartOfTheDocument() {
        String content = "Seite eins\fSeite zwei\fSeite drei";

        assertThat(ReadDocumentTool.render(content, 0, content.length(), true))
                .isEqualTo("--- page 1 ---\nSeite eins\n--- page 2 ---\nSeite zwei\n--- page 3 ---\nSeite drei");
        int second = content.indexOf("Seite zwei");
        assertThat(ReadDocumentTool.render(content, second, content.length(), true))
                .startsWith("--- page 2 ---\nSeite zwei");
    }

    @Test
    void aTextWithoutPageBreaksIsShownAsItIs() {
        assertThat(ReadDocumentTool.render("Kein Umbruch", 0, 12, false)).isEqualTo("Kein Umbruch");
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
