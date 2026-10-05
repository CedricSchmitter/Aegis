package dev.aegis.documents.domain.text;
import static org.assertj.core.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
class PassageRevisionTest {
    @Test void originalOffsetsPagesOverlapAndStableRevisionArePreserved() {
        String longPage="Paragraph.\n\n".repeat(350)+"Cancellation only at the final clause.";
        var revision=PassageRevision.of("fingerprint","ARCHIVE_PDF",List.of("First page", "",longPage),true);
        assertThat(revision).isEqualTo(PassageRevision.of("fingerprint","ARCHIVE_PDF",List.of("First page", "",longPage),true));
        assertThat(revision.passages()).allSatisfy(p -> {
            assertThat(p.original()).isEqualTo(revision.text().substring(p.start(),p.end()));
            assertThat(p.original().length()).isLessThanOrEqualTo(PassageRevision.MAX_PASSAGE);
        });
        assertThat(revision.passages().getLast().original()).endsWith("Cancellation only at the final clause.");
        assertThat(revision.passages().getLast().startPage()).isEqualTo(3);
        assertThat(revision.passages().get(2).start()).isLessThan(revision.passages().get(1).end());
        assertThat(PassageRevision.of("new-ocr","ARCHIVE_PDF",List.of("First page", "",longPage),true).revision())
                .isNotEqualTo(revision.revision());
    }
    @Test void unknownPagesStayUnknownAndNormalizationDoesNotRewriteOriginals() {
        var revision=PassageRevision.of("x","ARCHIVE_OCR",List.of("Müller: K-2026/004 Kündigungsfristen"),false);
        var p=revision.passages().getFirst();
        assertThat(p.startPage()).isNull(); assertThat(p.endPage()).isNull();
        assertThat(p.original()).contains("Müller"); assertThat(p.normalized()).contains("muller");
        assertThat(p.identifiers()).contains("k2026004","muller");
        assertThat(PassageRevision.of("x","ARCHIVE_OCR",List.of(" "),false).passages()).isEmpty();
    }
    @Test void unicodeAndOversizedParagraphsRemainContiguousAndBounded() {
        String original="😀".repeat(4000);
        var revision=PassageRevision.of("emoji","ARCHIVE_OCR",List.of(original),false);
        int covered=0;
        for (var p:revision.passages()) {
            assertThat(p.start()).isLessThanOrEqualTo(covered);
            assertThat(p.original().codePoints()).allMatch(c -> c==0x1f600);
            covered=p.end();
        }
        assertThat(covered).isEqualTo(original.length());
    }
}
