package dev.aegis.assistant.application.chat;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.documents.application.api.DocumentTextQuery.DocumentText;
import dev.aegis.shared.DocumentId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class QuoteMatchTest {
    static final DocumentText TEXT = new DocumentText(DocumentId.random(), "Stromvertrag", List.of(
            "Stadtwerke Muster GmbH\nStromliefervertrag Nr. 4711",
            "Mindestlaufzeit 24 Monate. Der Vertrag verlängert sich um jeweils 12 Monate, wenn er nicht mit einer "
                    + "Frist von 3 Monaten zum Ende der Laufzeit gekündigt wird. Die Kündigung bedarf der Text-",
            "form.\nSeite 3: „Grundpreis“ 14,50 EUR – monatlich"), true);

    @Test
    void aQuoteIsFoundDespiteCaseLineBreaksAndBlanks() {
        assertThat(QuoteMatch.find(TEXT, "der vertrag VERLÄNGERT sich  um jeweils\n12 Monate"))
                .contains(new QuoteMatch.Found(Optional.of(2)));
    }

    @Test
    void wordsHyphenatedAcrossLinesAndPagesMatchTheWholeWord() {
        assertThat(QuoteMatch.find(unpaged("zum Ende der Lauf-\nzeit gekündigt"), "zum Ende der Laufzeit gekündigt"))
                .isPresent();
        assertThat(QuoteMatch.find(TEXT, "Die Kündigung bedarf der Textform"))
                .as("the page where the quote starts").contains(new QuoteMatch.Found(Optional.of(2)));
    }

    @Test
    void typographicQuotesAndDashesMatchTheirPlainForms() {
        assertThat(QuoteMatch.find(TEXT, "\"Grundpreis\" 14,50 EUR - monatlich"))
                .contains(new QuoteMatch.Found(Optional.of(3)));
    }

    @Test
    void theFirstAndLastWordOfAPageBelongToThatPage() {
        assertThat(QuoteMatch.find(TEXT, "Stadtwerke")).contains(new QuoteMatch.Found(Optional.of(1)));
        assertThat(QuoteMatch.find(TEXT, "Nr. 4711")).contains(new QuoteMatch.Found(Optional.of(1)));
        assertThat(QuoteMatch.find(TEXT, "Mindestlaufzeit")).contains(new QuoteMatch.Found(Optional.of(2)));
        assertThat(QuoteMatch.find(TEXT, "monatlich")).contains(new QuoteMatch.Found(Optional.of(3)));
    }

    @Test
    void anEllipsisSkipsTextButThePartsMustAppearInOrder() {
        assertThat(QuoteMatch.find(TEXT, "Mindestlaufzeit 24 Monate ... Frist von 3 Monaten")).isPresent();
        assertThat(QuoteMatch.find(TEXT, "Frist von 3 Monaten … Mindestlaufzeit 24 Monate")).isEmpty();
    }

    @Test
    void changedWordsOrNumbersDoNotMatch() {
        assertThat(QuoteMatch.find(TEXT, "Mindestlaufzeit 12 Monate")).isEmpty();
        assertThat(QuoteMatch.find(TEXT, "...")).isEmpty();
    }

    @Test
    void aTextWithoutKnownPagesGivesNoPage() {
        assertThat(QuoteMatch.find(unpaged("Kfz-Haftpflicht, Beitrag 300 EUR."), "Beitrag 300 EUR"))
                .contains(new QuoteMatch.Found(Optional.empty()));
    }

    private static DocumentText unpaged(String text) {
        return DocumentText.unpaged(DocumentId.random(), "Versicherung", text);
    }
}
