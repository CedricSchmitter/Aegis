package dev.aegis.assistant.application.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.assistant.application.chat.PassageSearch.Passage;
import dev.aegis.assistant.application.chat.PassageSearch.Result;
import dev.aegis.documents.application.api.DocumentTextQuery.DocumentText;
import dev.aegis.shared.DocumentId;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class PassageSearchTest {

    private static DocumentText unpaged(String text) {
        return DocumentText.unpaged(DocumentId.random(), "Vertrag", text);
    }

    private static Result find(DocumentText document, String query) {
        return PassageSearch.find(document, PassageSearch.terms(query), 5);
    }

    private static String excerpt(DocumentText document, Passage passage) {
        return document.text().substring(passage.start(), passage.end());
    }

    @Test
    void umlautsAccentsAndCaseDoNotMatter() {
        DocumentText document = unpaged("Die KÜNDIGUNG ist schriftlich zu erklären. Kundigung per Fax. Café Kündigung");

        Result result = find(document, "kündigung");

        assertThat(result.missingTerms()).isEmpty();
        assertThat(result.passages()).singleElement().satisfies(passage -> {
            assertThat(passage.matches()).isEqualTo(3);
            assertThat(passage.terms()).containsExactly("kündigung");
        });
        assertThat(find(document, "CAFE").passages()).hasSize(1);
    }

    @Test
    void sharpSMatchesDoubleS() {
        DocumentText document = unpaged("Gemäss Abschnitt 4 gilt die Strasse als Lieferanschrift.");

        assertThat(find(document, "gemäß Straße").passages()).singleElement()
                .satisfies(passage -> assertThat(passage.terms()).containsExactly("gemäß", "Straße"));
        assertThat(find(unpaged("Die Straße"), "strasse").passages()).hasSize(1);
    }

    @Test
    void identifiersMatchWithOrWithoutSeparators() {
        for (String text : List.of("Vertragsnummer K2026004 vom", "Vertragsnummer K-2026/004 vom",
                "Vertragsnummer K 2026 004 vom", "Vertragsnummer k.2026.004 vom")) {
            DocumentText document = unpaged(text);
            assertThat(find(document, "K-2026/004").passages()).as(text).singleElement()
                    .satisfies(passage -> assertThat(excerpt(document, passage)).isEqualTo(text));
            assertThat(find(document, "K2026004").passages()).as(text).hasSize(1);
        }
        assertThat(find(unpaged("E-Mail an uns"), "E-Mail").passages()).hasSize(1);
        assertThat(find(unpaged("Email an uns"), "E-Mail").passages()).hasSize(1);
    }

    @Test
    void longWordsMatchInsideCompoundsAndShortWordsOnlyWhole() {
        DocumentText document = unpaged("Die Kündigungsfrist beträgt drei Monate. Kfz-Versicherung. Angebot.");

        assertThat(find(document, "frist").passages()).hasSize(1);
        assertThat(find(document, "kfz").passages()).hasSize(1);
        assertThat(find(document, "an").passages()).isEmpty();
        assertThat(find(document, "ang").passages()).isEmpty();
        assertThat(find(document, "ange").passages()).hasSize(1);
    }

    @Test
    void aWordHyphenatedAcrossALineStillMatches() {
        DocumentText document = unpaged("Die Kündi-\n  gungsfrist beträgt drei Monate.");

        assertThat(find(document, "Kündigungsfrist").passages()).singleElement()
                .satisfies(passage -> assertThat(passage.matches()).isEqualTo(1));
    }

    @Test
    void passagesWithMoreOfTheWordsRankFirstAndPassagesNeverOverlap() {
        String filler = "Lorem ipsum dolor sit amet. ".repeat(60);
        String text = "Inhalt: Kündigung, Laufzeit, Preise.\n" + filler
                + "Preise werden jährlich angepasst.\n" + filler
                + "Die Kündigung ist mit einer Frist von drei Monaten zum Ende der Laufzeit möglich.\n" + filler
                + "Kündigung";
        DocumentText document = unpaged(text);

        Result result = PassageSearch.find(document, PassageSearch.terms("Kündigung Frist Monaten"), 5);

        assertThat(result.passages()).hasSize(3);
        Passage best = result.passages().getFirst();
        assertThat(best.terms()).containsExactly("Kündigung", "Frist", "Monaten");
        assertThat(excerpt(document, best)).contains("Die Kündigung ist mit einer Frist von drei Monaten");
        assertThat(result.passages().get(1).terms()).containsExactly("Kündigung");
        assertThat(result.passages().get(1).start()).as("ties go to the earlier passage")
                .isLessThan(result.passages().get(2).start());
        List<Passage> byPosition = new ArrayList<>(result.passages());
        byPosition.sort((a, b) -> Integer.compare(a.start(), b.start()));
        for (int i = 1; i < byPosition.size(); i++) {
            assertThat(byPosition.get(i).start()).isGreaterThanOrEqualTo(byPosition.get(i - 1).end());
        }
    }

    @Test
    void excerptsAreBoundedEndAtBlanksAndContainTheirMatches() {
        String text = "wort ".repeat(400) + "Sonderkündigungsrecht " + "wort ".repeat(400);
        DocumentText document = unpaged(text);

        Passage passage = find(document, "Sonderkündigungsrecht").passages().getFirst();

        String excerpt = excerpt(document, passage);
        assertThat(excerpt).contains("Sonderkündigungsrecht").startsWith("wort").endsWith("wort");
        assertThat(excerpt.length()).isLessThanOrEqualTo(2 * PassageSearch.CONTEXT + "Sonderkündigungsrecht".length());
        assertThat(passage.start()).isGreaterThan(0);
        assertThat(passage.end()).isLessThan(text.length());

        String unbroken = "x".repeat(1_000) + "Ziel" + "y".repeat(1_000);
        Passage cut = find(unpaged(unbroken), "ziel").passages().getFirst();
        assertThat(cut.end() - cut.start()).isEqualTo(2 * PassageSearch.CONTEXT + 4);
    }

    @Test
    void matchesFarApartBecomeSeparatePassagesAndTheRestIsCountedAsOmitted() {
        String text = ("Rechnung " + "x ".repeat(PassageSearch.SPAN + PassageSearch.CONTEXT)).repeat(9);
        DocumentText document = unpaged(text);

        Result result = PassageSearch.find(document, PassageSearch.terms("Rechnung"), 3);

        assertThat(result.passages()).hasSize(3);
        assertThat(result.omitted()).isEqualTo(6);
        assertThat(PassageSearch.find(document, PassageSearch.terms("Rechnung"), 20).passages()).hasSize(9);
    }

    @Test
    void aPassageNamesThePageOfItsFirstMatch() {
        DocumentText document = new DocumentText(DocumentId.random(), "Vertrag",
                List.of("Seite eins ohne Treffer", "Seite zwei", "Kündigung auf Seite drei"), true);

        Passage passage = find(document, "Kündigung").passages().getFirst();

        assertThat(passage.page()).isEqualTo(OptionalInt.of(3));
        assertThat(find(unpaged("Kündigung"), "Kündigung").passages().getFirst().page()).isEmpty();
    }

    @Test
    void wordsThatOccurNowhereAreReported() {
        Result result = find(unpaged("Die Kündigung"), "Kündigung Widerruf");

        assertThat(result.missingTerms()).containsExactly("Widerruf");
        assertThat(find(unpaged("Die Kündigung"), "Widerruf").passages()).isEmpty();
        assertThat(find(unpaged(""), "Widerruf").passages()).isEmpty();
    }

    @Test
    void termsAreDistinctAndTooShortWordsAreDropped() {
        assertThat(PassageSearch.terms("  Kündigung kuendigung KÜNDIGUNG a , K-1  "))
                .extracting(PassageSearch.Term::word).containsExactly("Kündigung", "kuendigung", "K-1");
        assertThat(PassageSearch.terms("- a .")).isEmpty();
        assertThatThrownBy(() -> PassageSearch.find(unpaged("x"), List.of(), 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PassageSearch.find(unpaged("x"), PassageSearch.terms("eins zwei drei vier fünf sechs "
                + "sieben acht neun"), 5)).isInstanceOf(IllegalArgumentException.class);
    }
}
