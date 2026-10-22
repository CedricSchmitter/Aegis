package dev.aegis.documents.domain.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.documents.domain.text.ArchiveTextCheck.Amount;
import dev.aegis.shared.DocumentId;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ArchiveTextCheckTest {
    private static final DocumentId ID = DocumentId.of("6b0c5b1e-5d7e-4b52-9a39-0c4f3f0d1a11");
    private static final Instant NOW = Instant.parse("2026-09-23T10:00:00Z");

    @Test
    void anEmptyOrBlankArchiveTextMeansNoText() {
        assertThat(ArchiveTextCheck.measured(ID, 7, "", false, NOW).amount()).contains(Amount.NONE);
        assertThat(ArchiveTextCheck.measured(ID, 7, null, false, NOW).amount()).contains(Amount.NONE);
        // Stray marks OCR reads from page edges and noise are not text.
        assertThat(ArchiveTextCheck.measured(ID, 7, " \n\t| — ~ . , ; ' \" _ \f", false, NOW).amount())
                .contains(Amount.NONE);
    }

    @Test
    void lettersAndDigitsOfAnyScriptCount() {
        ArchiveTextCheck check = ArchiveTextCheck.measured(ID, 7, "Straße 12, Größe: ÄÖÜ – ÉÈ 日本", false, NOW);
        assertThat(check.recognizedCharacters()).isEqualTo(20);
        assertThat(check.checkedAt()).isEqualTo(NOW);
        assertThat(check.nextAttemptAt()).isNull();
        assertThat(check.isMeasured()).isTrue();
    }

    @Test
    void fewerThanFiftyLettersAndDigitsAreLittleText() {
        assertThat(ArchiveTextCheck.measured(ID, 7, "a".repeat(49), false, NOW).amount())
                .contains(Amount.LITTLE);
        assertThat(ArchiveTextCheck.measured(ID, 7, "a".repeat(50), false, NOW).amount())
                .contains(Amount.RECOGNIZED);
        assertThat(ArchiveTextCheck.measured(ID, 7, "Sehr geehrte Frau Muster, anbei die Rechnung Nr. 4711 für "
                + "den Monat September.", false, NOW).amount()).contains(Amount.RECOGNIZED);
    }

    @Test
    void aFailedAttemptWaitsLongerEachTimeAndAtMostADay() {
        ArchiveTextCheck first = ArchiveTextCheck.failed(ID, 7, Optional.empty(), NOW);
        assertThat(first.isMeasured()).isFalse();
        assertThat(first.amount()).isEmpty();
        assertThat(first.failedAttempts()).isEqualTo(1);
        assertThat(first.nextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));

        Instant later = NOW.plus(Duration.ofMinutes(6));
        ArchiveTextCheck second = ArchiveTextCheck.failed(ID, 7, Optional.of(first), later);
        assertThat(second.failedAttempts()).isEqualTo(2);
        assertThat(second.nextAttemptAt()).isEqualTo(later.plus(Duration.ofMinutes(10)));

        assertThat(ArchiveTextCheck.retryDelay(9)).isEqualTo(Duration.ofMinutes(5 * 256));
        assertThat(ArchiveTextCheck.retryDelay(10)).isEqualTo(Duration.ofDays(1));
        assertThat(ArchiveTextCheck.retryDelay(1000)).isEqualTo(Duration.ofDays(1));
    }

    @Test
    void aFailureNeverReplacesAMeasurementOfTheSameArchiveDocument() {
        ArchiveTextCheck measured = ArchiveTextCheck.measured(ID, 7, "", false, NOW);
        assertThat(ArchiveTextCheck.failed(ID, 7, Optional.of(measured), NOW.plusSeconds(60))).isEqualTo(measured);
    }

    @Test
    void aMeasurementCarriesTheFingerprintOfTheTextAndAnOlderOneWithoutItIsReadAgain() {
        ArchiveTextCheck measured = ArchiveTextCheck.measured(ID, 7, "Rechnung vom Mai", false, NOW);
        assertThat(measured.fingerprint()).isEqualTo(TextFingerprint.of("Rechnung vom Mai"));
        assertThat(measured.isComplete()).isTrue();

        ArchiveTextCheck before = new ArchiveTextCheck(ID, 7, 480, true, NOW, 0, null);
        assertThat(before.isMeasured()).isTrue();
        assertThat(before.isComplete()).as("stored before fingerprints existed").isFalse();
        ArchiveTextCheck failed = ArchiveTextCheck.failed(ID, 7, Optional.of(before), NOW.plusSeconds(60));
        assertThat(failed.recognizedCharacters()).isEqualTo(480);
        assertThat(failed.keepsArchiveVersion()).isTrue();
        assertThat(failed.isMeasured()).isTrue();
        assertThat(failed.checkedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThatThrownBy(() -> new ArchiveTextCheck(ID, 7, null, null, NOW, 1, NOW, measured.fingerprint()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCheckOfAnotherArchiveDocumentSaysNothingAboutThisOne() {
        ArchiveTextCheck old = ArchiveTextCheck.failed(ID, 7, Optional.empty(), NOW);
        assertThat(old.appliesTo(7)).isTrue();
        assertThat(old.appliesTo(8)).isFalse();

        ArchiveTextCheck restarted = ArchiveTextCheck.failed(ID, 8, Optional.of(old), NOW);
        assertThat(restarted.failedAttempts()).isEqualTo(1);
        ArchiveTextCheck overMeasured = ArchiveTextCheck.failed(ID, 8,
                Optional.of(ArchiveTextCheck.measured(ID, 7, "text", false, NOW)), NOW);
        assertThat(overMeasured.isMeasured()).isFalse();
        assertThat(overMeasured.paperlessDocumentId()).isEqualTo(8);
    }

    @Test
    void theCheckRecordsWhetherTheArchiveKeepsASearchableArchiveVersion() {
        ArchiveTextCheck scanned = ArchiveTextCheck.measured(ID, 7, "Rechnung", true, NOW);
        assertThat(scanned.archiveVersion()).isTrue();
        assertThat(scanned.keepsArchiveVersion()).isTrue();
        ArchiveTextCheck bornDigital = ArchiveTextCheck.measured(ID, 7, "Rechnung", false, NOW);
        assertThat(bornDigital.archiveVersion()).isFalse();
        assertThat(bornDigital.keepsArchiveVersion()).isFalse();
        assertThat(ArchiveTextCheck.failed(ID, 7, Optional.empty(), NOW).keepsArchiveVersion()).isFalse();
    }

    @Test
    void aCountReadBeforeTheArchiveVersionWasRecordedIsKeptUntilTheDocumentIsReadAgain() {
        // Checks stored before the archive version existed carry a count and wait for their next attempt.
        ArchiveTextCheck earlier = new ArchiveTextCheck(ID, 7, 480, null, NOW, 0, NOW);
        assertThat(earlier.isMeasured()).isFalse();
        assertThat(earlier.amount()).contains(Amount.RECOGNIZED);

        ArchiveTextCheck failed = ArchiveTextCheck.failed(ID, 7, Optional.of(earlier), NOW.plusSeconds(60));
        assertThat(failed.recognizedCharacters()).as("a failed re-read keeps the count").isEqualTo(480);
        assertThat(failed.archiveVersion()).isNull();
        assertThat(failed.failedAttempts()).isEqualTo(1);
        assertThat(failed.nextAttemptAt()).isEqualTo(NOW.plusSeconds(60).plus(Duration.ofMinutes(5)));

        assertThat(ArchiveTextCheck.failed(ID, 8, Optional.of(earlier), NOW).recognizedCharacters())
                .as("a count of another archive document says nothing about this one").isNull();
    }

    @Test
    void aCheckIsEitherMeasuredOrWaitingForItsNextAttempt() {
        assertThatThrownBy(() -> new ArchiveTextCheck(ID, 7, 3, false, NOW, 0, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ArchiveTextCheck(ID, 7, null, null, NOW, 1, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ArchiveTextCheck(ID, 7, 3, null, NOW, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ArchiveTextCheck(ID, 7, -1, false, NOW, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ArchiveTextCheck(ID, 7, null, true, NOW, 0, null))
                .as("the text and the archive version are read together")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
