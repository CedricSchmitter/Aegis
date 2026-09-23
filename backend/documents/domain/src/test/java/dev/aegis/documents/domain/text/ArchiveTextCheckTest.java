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
        assertThat(ArchiveTextCheck.measured(ID, 7, "", NOW).amount()).contains(Amount.NONE);
        assertThat(ArchiveTextCheck.measured(ID, 7, null, NOW).amount()).contains(Amount.NONE);
        // Stray marks OCR reads from page edges and noise are not text.
        assertThat(ArchiveTextCheck.measured(ID, 7, " \n\t| — ~ . , ; ' \" _ \f", NOW).amount())
                .contains(Amount.NONE);
    }

    @Test
    void lettersAndDigitsOfAnyScriptCount() {
        ArchiveTextCheck check = ArchiveTextCheck.measured(ID, 7, "Straße 12, Größe: ÄÖÜ – ÉÈ 日本", NOW);
        assertThat(check.recognizedCharacters()).isEqualTo(20);
        assertThat(check.checkedAt()).isEqualTo(NOW);
        assertThat(check.nextAttemptAt()).isNull();
        assertThat(check.isMeasured()).isTrue();
    }

    @Test
    void fewerThanFiftyLettersAndDigitsAreLittleText() {
        assertThat(ArchiveTextCheck.measured(ID, 7, "a".repeat(49), NOW).amount()).contains(Amount.LITTLE);
        assertThat(ArchiveTextCheck.measured(ID, 7, "a".repeat(50), NOW).amount()).contains(Amount.RECOGNIZED);
        assertThat(ArchiveTextCheck.measured(ID, 7, "Sehr geehrte Frau Muster, anbei die Rechnung Nr. 4711 für "
                + "den Monat September.", NOW).amount()).contains(Amount.RECOGNIZED);
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
        ArchiveTextCheck measured = ArchiveTextCheck.measured(ID, 7, "", NOW);
        assertThat(ArchiveTextCheck.failed(ID, 7, Optional.of(measured), NOW.plusSeconds(60))).isEqualTo(measured);
    }

    @Test
    void aCheckOfAnotherArchiveDocumentSaysNothingAboutThisOne() {
        ArchiveTextCheck old = ArchiveTextCheck.failed(ID, 7, Optional.empty(), NOW);
        assertThat(old.appliesTo(7)).isTrue();
        assertThat(old.appliesTo(8)).isFalse();

        ArchiveTextCheck restarted = ArchiveTextCheck.failed(ID, 8, Optional.of(old), NOW);
        assertThat(restarted.failedAttempts()).isEqualTo(1);
        ArchiveTextCheck overMeasured = ArchiveTextCheck.failed(ID, 8,
                Optional.of(ArchiveTextCheck.measured(ID, 7, "text", NOW)), NOW);
        assertThat(overMeasured.isMeasured()).isFalse();
        assertThat(overMeasured.paperlessDocumentId()).isEqualTo(8);
    }

    @Test
    void aCheckIsEitherMeasuredOrWaitingForItsNextAttempt() {
        assertThatThrownBy(() -> new ArchiveTextCheck(ID, 7, 3, NOW, 0, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ArchiveTextCheck(ID, 7, null, NOW, 1, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ArchiveTextCheck(ID, 7, -1, NOW, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
