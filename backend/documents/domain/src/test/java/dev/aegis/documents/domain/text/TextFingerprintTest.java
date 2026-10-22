package dev.aegis.documents.domain.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;
import org.junit.jupiter.api.Test;

class TextFingerprintTest {

    /** A synthetic monthly invoice: the wording stays, dates, amounts and numbers change from month to month. */
    private static String invoice(int month, String amount, String consumption) {
        return """
                Stadtwerke Musterstadt GmbH · Postfach 12 34 · 12345 Musterstadt
                Familie Beispiel
                Musterweg 7
                12345 Musterstadt
                Kundennummer 4711-0815   Vertragskonto 300 222 111
                Rechnung Nr. RE-2026-%1$02d-88%1$02d1 vom %1$02d.01.2026
                Abrechnungszeitraum 01.%1$02d.2026 bis 30.%1$02d.2026
                Sehr geehrte Damen und Herren,
                für den oben genannten Zeitraum berechnen wir Ihnen die Lieferung von Strom wie folgt.
                Ihr Verbrauch im Abrechnungszeitraum betrug %3$s kWh. Der Arbeitspreis beträgt 32,40 Cent je
                Kilowattstunde, der Grundpreis 12,90 Euro im Monat. Alle Preise enthalten die gesetzliche
                Umsatzsteuer von 19 Prozent. Der Rechnungsbetrag von %2$s Euro wird am 15.%1$02d.2026 von Ihrem
                Konto bei der Musterbank eingezogen. Bitte sorgen Sie für ausreichende Deckung. Bei Fragen zu
                dieser Rechnung erreichen Sie unseren Kundenservice montags bis freitags von 8 bis 18 Uhr unter
                der Telefonnummer 0800 123 456 oder per E-Mail. Hinweise zum Widerspruch und zur Schlichtungsstelle
                finden Sie auf der Rückseite dieser Rechnung. Wir danken Ihnen für Ihr Vertrauen.
                Mit freundlichen Grüßen
                Ihre Stadtwerke Musterstadt
                """.formatted(month, amount, consumption);
    }

    private static final String LETTER = """
            Allgemeine Versicherung AG · Versicherungsschein VS-2026-004711
            Herrn Max Beispiel, Musterweg 7, 12345 Musterstadt
            Musterstadt, 14.03.2026
            Ihre Hausratversicherung: Anpassung des Beitrags zum 01.05.2026
            Sehr geehrter Herr Beispiel,
            wie in Paragraph 16 der Versicherungsbedingungen vereinbart, überprüfen wir jährlich die Entwicklung der
            Schadenaufwendungen. Für Ihren Vertrag ergibt sich daraus eine Anpassung des Beitrags. Ab dem 01.05.2026
            beträgt Ihr Jahresbeitrag 187,40 Euro statt bisher 172,10 Euro. Die Versicherungssumme von 65.000 Euro
            bleibt unverändert. Sie können den Vertrag innerhalb eines Monats nach Zugang dieser Mitteilung zum
            Zeitpunkt des Wirksamwerdens der Änderung kündigen. Eine Kündigung muss uns in Textform erreichen.
            Wenn Sie den Vertrag fortführen möchten, müssen Sie nichts weiter tun. Für Rückfragen steht Ihnen Ihr
            Ansprechpartner unter der Telefonnummer 0221 555 0100 zur Verfügung.
            Mit freundlichen Grüßen
            Allgemeine Versicherung AG
            """;

    /**
     * The letter as a second scan reads it: other line breaks, a misread letter in about one of 25 longer words and
     * one misread digit.
     */
    private static String rescan(String text, long seed) {
        Random random = new Random(seed);
        StringBuilder out = new StringBuilder();
        String[] words = text.replace('\n', ' ').split(" ");
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (word.length() > 4 && random.nextInt(25) == 0) {
                int at = 1 + random.nextInt(word.length() - 2);
                if (Character.isLetter(word.charAt(at))) {
                    word = word.substring(0, at) + "l" + word.substring(at + 1);
                }
            }
            out.append(word).append(i % 11 == 10 ? "\n" : " ");
        }
        return out.toString().replace("172,10", "172,18");
    }

    @Test
    void theSameLetterScannedAgainIsANearDuplicate() {
        TextFingerprint original = TextFingerprint.of(LETTER);
        for (long seed = 1; seed <= 200; seed++) {
            TextFingerprint again = TextFingerprint.of(rescan(LETTER, seed));
            assertThat(again.nearDuplicateOf(original)).as("seed %d: words %.2f, numbers %.2f", seed,
                    again.wordSimilarity(original), again.numberSimilarity(original)).isTrue();
        }
        assertThat(original.nearDuplicateOf(TextFingerprint.of(LETTER))).isTrue();
        assertThat(original.wordSimilarity(TextFingerprint.of(LETTER))).isEqualTo(1.0);
    }

    @Test
    void caseAccentsAndSharpSDoNotMatter() {
        TextFingerprint original = TextFingerprint.of(LETTER);
        String folded = LETTER.toUpperCase().replace("Ü", "U").replace("Ä", "A").replace("ß", "SS");
        assertThat(TextFingerprint.of(folded)).isEqualTo(original);
    }

    @Test
    void monthlyInvoicesWithTheSameWordingAreDistinctRecords() {
        TextFingerprint january = TextFingerprint.of(invoice(1, "84,17", "236"));
        TextFingerprint february = TextFingerprint.of(invoice(2, "79,52", "221"));

        assertThat(january.wordSimilarity(february)).as("the wording alone looks alike").isGreaterThan(0.5);
        assertThat(january.nearDuplicateOf(february)).as("numbers %.2f", january.numberSimilarity(february))
                .isFalse();
        assertThat(january.nearDuplicateOf(TextFingerprint.of(invoice(1, "84,17", "236")))).isTrue();
    }

    @Test
    void unrelatedDocumentsAndShortTextsAreNoNearDuplicates() {
        TextFingerprint letter = TextFingerprint.of(LETTER);
        TextFingerprint invoice = TextFingerprint.of(invoice(3, "91,03", "250"));
        assertThat(letter.nearDuplicateOf(invoice)).isFalse();
        assertThat(letter.wordSimilarity(invoice)).isLessThan(0.2);

        TextFingerprint receipt = TextFingerprint.of("Bäckerei Korn Brötchen 2,40 EUR Danke");
        assertThat(receipt.comparable()).isFalse();
        assertThat(receipt.nearDuplicateOf(TextFingerprint.of("Bäckerei Korn Brötchen 2,40 EUR Danke"))).isFalse();
        assertThat(TextFingerprint.of("").comparable()).isFalse();
        assertThat(TextFingerprint.of(null).comparable()).isFalse();
    }

    @Test
    void numbersOnOnlyOneSideKeepTextsApart() {
        String words = LETTER.replaceAll("[0-9]", "");
        TextFingerprint without = TextFingerprint.of(words);
        assertThat(without.numberSimilarity(TextFingerprint.of(words))).isEqualTo(1.0);
        assertThat(without.numberSimilarity(TextFingerprint.of(LETTER))).isZero();
    }

    @Test
    void aStoredFingerprintReadsBackAndAnotherVersionIsIgnored() {
        TextFingerprint original = TextFingerprint.of(LETTER);
        byte[] stored = original.encode();

        assertThat(TextFingerprint.decode(stored)).isEqualTo(original);
        stored[0] = (byte) (TextFingerprint.VERSION + 1);
        assertThat(TextFingerprint.decode(stored)).isNull();
        assertThat(TextFingerprint.decode(new byte[3])).isNull();
        assertThat(TextFingerprint.decode(null)).isNull();
    }
}
