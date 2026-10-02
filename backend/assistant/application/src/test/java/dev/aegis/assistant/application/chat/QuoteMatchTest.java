package dev.aegis.assistant.application.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class QuoteMatchTest {
    static final String TEXT = "Stadtwerke Muster GmbH\nStromliefervertrag Nr. 4711\n\fMindestlaufzeit 24 Monate. "
            + "Der Vertrag verlängert sich um jeweils 12 Monate, wenn er nicht mit einer Frist von 3 Monaten zum "
            + "Ende der Lauf-\nzeit gekündigt wird.\n\fSeite 3: „Grundpreis“ 14,50 EUR – monatlich";

    @Test
    void aQuoteIsFoundDespiteCaseLineBreaksAndBlanks() {
        assertThat(QuoteMatch.find(TEXT, "der vertrag VERLÄNGERT sich  um jeweils\n12 Monate"))
                .contains(new QuoteMatch.Found(Optional.of(2)));
    }

    @Test
    void wordsHyphenatedAcrossLinesMatchTheWholeWord() {
        assertThat(QuoteMatch.find(TEXT, "zum Ende der Laufzeit gekündigt")).isPresent();
    }

    @Test
    void typographicQuotesAndDashesMatchTheirPlainForms() {
        assertThat(QuoteMatch.find(TEXT, "\"Grundpreis\" 14,50 EUR - monatlich"))
                .contains(new QuoteMatch.Found(Optional.of(3)));
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
    void aTextWithoutPageBreaksGivesNoPage() {
        assertThat(QuoteMatch.find("Kfz-Haftpflicht, Beitrag 300 EUR.", "Beitrag 300 EUR"))
                .contains(new QuoteMatch.Found(Optional.empty()));
    }
}
