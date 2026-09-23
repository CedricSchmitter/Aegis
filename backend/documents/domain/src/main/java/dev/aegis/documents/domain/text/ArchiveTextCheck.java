package dev.aegis.documents.domain.text;

import dev.aegis.shared.DocumentId;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Whether the archive recognized text in an archived document (issue #87, ADR-0012). It is derived from the OCR text
 * of one archive document and keeps only the number of letters and digits in it, never the text itself, so it can
 * always be measured again from the archive. A check that could not read the text keeps no count and names when the
 * next automatic attempt is due; a failed attempt never replaces a count already measured for the same archive
 * document.
 *
 * @param recognizedCharacters letters and digits in the archive text; null while no attempt succeeded
 * @param checkedAt            the latest attempt, successful or not
 * @param failedAttempts       failed attempts since the last successful one for this archive document
 * @param nextAttemptAt        when a failed check is tried again; null once measured
 */
public record ArchiveTextCheck(DocumentId documentId, int paperlessDocumentId, Integer recognizedCharacters,
        Instant checkedAt, int failedAttempts, Instant nextAttemptAt) {

    /**
     * Fewer letters and digits than this count as almost no text. A one-page letter has several hundred; the rotated
     * and dark test photos of issue #81 gave none at all.
     */
    public static final int LITTLE_TEXT_BELOW = 50;
    static final Duration RETRY_BASE = Duration.ofMinutes(5);
    static final Duration RETRY_MAX = Duration.ofDays(1);

    /** How much text the archive recognized. */
    public enum Amount {
        /** No letter or digit at all, for example an empty OCR text. */
        NONE,
        /** Some letters or digits, but fewer than {@link #LITTLE_TEXT_BELOW}. */
        LITTLE,
        RECOGNIZED
    }

    public ArchiveTextCheck {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(checkedAt, "checkedAt");
        if (recognizedCharacters != null && recognizedCharacters < 0) {
            throw new IllegalArgumentException("recognized characters must not be negative");
        }
        if (failedAttempts < 0) {
            throw new IllegalArgumentException("failed attempts must not be negative");
        }
        if ((recognizedCharacters == null) == (nextAttemptAt == null)) {
            throw new IllegalArgumentException("a check is either measured or waiting for its next attempt");
        }
    }

    /** Measures the text the archive recognized in its document {@code paperlessDocumentId}. */
    public static ArchiveTextCheck measured(DocumentId documentId, int paperlessDocumentId, String archiveText,
            Instant now) {
        return new ArchiveTextCheck(documentId, paperlessDocumentId, countRecognized(archiveText), now, 0, null);
    }

    /**
     * Records that the archive text could not be read. Each further failure for the same archive document waits twice
     * as long before the next attempt, at most a day, so an archive that keeps refusing one document is asked rarely.
     * A count already measured for this archive document stays as it is.
     */
    public static ArchiveTextCheck failed(DocumentId documentId, int paperlessDocumentId,
            Optional<ArchiveTextCheck> previous, Instant now) {
        Optional<ArchiveTextCheck> same = previous.filter(check -> check.appliesTo(paperlessDocumentId));
        if (same.filter(ArchiveTextCheck::isMeasured).isPresent()) {
            return same.get();
        }
        int attempts = same.map(ArchiveTextCheck::failedAttempts).orElse(0) + 1;
        return new ArchiveTextCheck(documentId, paperlessDocumentId, null, now, attempts,
                now.plus(retryDelay(attempts)));
    }

    /** Whether this check belongs to the given archive document; a check of another one says nothing about it. */
    public boolean appliesTo(int paperlessDocumentId) {
        return this.paperlessDocumentId == paperlessDocumentId;
    }

    public boolean isMeasured() {
        return recognizedCharacters != null;
    }

    /** The amount of recognized text, or empty while no attempt succeeded. */
    public Optional<Amount> amount() {
        if (recognizedCharacters == null) {
            return Optional.empty();
        }
        if (recognizedCharacters == 0) {
            return Optional.of(Amount.NONE);
        }
        return Optional.of(recognizedCharacters < LITTLE_TEXT_BELOW ? Amount.LITTLE : Amount.RECOGNIZED);
    }

    /**
     * Counts letters and digits of any script. Whitespace, punctuation and the stray lines OCR reads from edges and
     * noise do not count, so a text of only such marks counts as no text.
     */
    static int countRecognized(String text) {
        if (text == null) {
            return 0;
        }
        return (int) text.codePoints().filter(Character::isLetterOrDigit).count();
    }

    static Duration retryDelay(int failedAttempts) {
        long factor = 1L << Math.max(0, Math.min(failedAttempts - 1, 20));
        Duration delay = RETRY_BASE.multipliedBy(factor);
        return delay.compareTo(RETRY_MAX) > 0 ? RETRY_MAX : delay;
    }
}
