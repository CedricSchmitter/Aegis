package dev.aegis.documents.domain.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.documents.domain.analysis.AnalysisTypes.DateStatus;
import dev.aegis.documents.domain.analysis.AnalysisTypes.Flag;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ClassificationPolicyTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 18);
    private static final String INVOICE = """
            Telekom Deutschland GmbH
            Rechnungsdatum: 17.09.2026   Kundennummer 123456789
            Ihre Rechnung für Internet (MagentaZuhause) Leistungszeitraum 01.09.2026 - 30.09.2026
            Zahlbar bis 01.10.2026""";

    @Test
    void quotesMustBeVerbatimDocumentText() {
        assertThat(Evidence.quoted("Telekom  Deutschland\nGmbH", INVOICE)).isTrue();
        assertThat(Evidence.quoted("Vodafone GmbH", INVOICE)).isFalse();
        assertThat(Evidence.quoted("Te", INVOICE)).isFalse();
        assertThat(Evidence.quoted(null, INVOICE)).isFalse();
    }

    @Test
    void theIssueDateCountsOnlyWhenItsQuoteShowsIt() {
        assertThat(ClassificationPolicy.documentDate(LocalDate.of(2026, 9, 17), "Rechnungsdatum: 17.09.2026", false,
                INVOICE, TODAY).status()).isEqualTo(DateStatus.FOUND);
        // A due date quoted as evidence for another day is not accepted.
        assertThat(ClassificationPolicy.documentDate(LocalDate.of(2026, 9, 17), "Zahlbar bis 01.10.2026", false,
                INVOICE, TODAY).status()).isEqualTo(DateStatus.UNVERIFIED);
        // A date invented by the model without a quote is not used either.
        assertThat(ClassificationPolicy.documentDate(LocalDate.of(2026, 9, 18), null, false, INVOICE, TODAY)
                .usable()).isNull();
    }

    @Test
    void conflictingUnknownAndImplausibleDatesAreNotUsable() {
        assertThat(ClassificationPolicy.documentDate(LocalDate.of(2026, 9, 17), "Rechnungsdatum: 17.09.2026", true,
                INVOICE, TODAY).status()).isEqualTo(DateStatus.CONFLICTING);
        assertThat(ClassificationPolicy.documentDate(null, null, false, INVOICE, TODAY).status())
                .isEqualTo(DateStatus.UNKNOWN);
        String future = "Datum 17.09.2031";
        assertThat(ClassificationPolicy.documentDate(LocalDate.of(2031, 9, 17), future, false, future, TODAY)
                .status()).isEqualTo(DateStatus.UNVERIFIED);
    }

    @Test
    void germanAndEnglishDateNotationsAreRecognized() {
        String text = "Berlin, 3. März 2026 / London, March 3, 2026 / 2026-03-03";
        LocalDate date = LocalDate.of(2026, 3, 3);

        assertThat(Evidence.datesTo(date, "Berlin, 3. März 2026", text)).isTrue();
        assertThat(Evidence.datesTo(date, "London, March 3, 2026", text)).isTrue();
        assertThat(Evidence.datesTo(date, "2026-03-03", text)).isTrue();
    }

    @Test
    void applyingNeedsAnEvidencedHighConfidenceClaimWithoutDoubt() {
        assertThat(ClassificationPolicy.applies(false, 0.9, true)).isTrue();
        assertThat(ClassificationPolicy.applies(false, 0.9, false)).isFalse();
        assertThat(ClassificationPolicy.applies(false, 0.8, true)).isFalse();
        assertThat(ClassificationPolicy.applies(false, null, true)).isFalse();
        assertThat(ClassificationPolicy.applies(ClassificationPolicy.doubtful(Set.of(Flag.EMBEDDED_INSTRUCTIONS)),
                0.99, true)).isFalse();
        assertThat(ClassificationPolicy.offersCandidate(0.6, true)).isTrue();
        assertThat(ClassificationPolicy.offersCandidate(0.95, false)).isFalse();
    }

    @Test
    void aDocumentIsFiledAutomaticallyOnlyWhenTheFolderMatchesItsLabels() {
        // The folder implies "Versicherung"; the document carries it, so the folder itself is evidence.
        assertThat(ClassificationPolicy.placesAutomatically(false, 0.9, true, true, false, Set.of("Versicherung"),
                Set.of("Versicherung", "Rechnung"))).isTrue();
        // A folder that would add a label the document does not have is only a suggestion.
        assertThat(ClassificationPolicy.placesAutomatically(false, 0.9, true, true, false,
                Set.of("Versicherung", "Auto"), Set.of("Versicherung"))).isFalse();
        // A folder without implied labels is never evidence on its own (ADR-0002).
        assertThat(ClassificationPolicy.placesAutomatically(false, 0.99, true, true, false, Set.of(),
                Set.of("Versicherung"))).isFalse();
    }

    @Test
    void doubtLowConfidenceMissingQuotesAndMemberDecisionsPreventAnAutomaticMove() {
        Set<String> implied = Set.of("Versicherung");
        Set<String> effective = Set.of("Versicherung");

        assertThat(ClassificationPolicy.placesAutomatically(true, 0.99, true, true, false, implied, effective))
                .as("doubt flag").isFalse();
        assertThat(ClassificationPolicy.placesAutomatically(false, 0.84, true, true, false, implied, effective))
                .as("below the confidence rule").isFalse();
        assertThat(ClassificationPolicy.placesAutomatically(false, null, true, true, false, implied, effective))
                .as("no confidence reported").isFalse();
        assertThat(ClassificationPolicy.placesAutomatically(false, 0.99, false, true, false, implied, effective))
                .as("no quote from the text").isFalse();
        assertThat(ClassificationPolicy.placesAutomatically(false, 0.99, true, false, false, implied, effective))
                .as("already in a folder").isFalse();
        assertThat(ClassificationPolicy.placesAutomatically(false, 0.99, true, true, true, implied, effective))
                .as("a member placed or moved it before").isFalse();
    }
}
