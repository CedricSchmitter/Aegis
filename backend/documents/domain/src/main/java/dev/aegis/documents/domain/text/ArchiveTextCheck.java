package dev.aegis.documents.domain.text;

import dev.aegis.shared.DocumentId;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Whether the archive recognized text in an archived document (ADR-0012) and whether it keeps a separate
 * searchable archive version of it (ADR-0016). Both are read from one archive document in one request. The check keeps
 * only the number of letters and digits in the OCR text, never the text itself, so it can always be measured again
 * from the archive. A check that could not read the archive document names when the next automatic attempt is due; a
 * failed attempt never replaces a measurement of the same archive document.
 *
 * @param recognizedCharacters letters and digits in the archive text; null while no attempt succeeded. A check waiting
 *                             for its next attempt may keep a count from before the archive version was recorded.
 * @param archiveVersion       whether the archive keeps a searchable archive version; null until measured
 * @param checkedAt            the latest attempt, successful or not
 * @param failedAttempts       failed attempts since the last successful one for this archive document
 * @param nextAttemptAt        when an incomplete check is tried again; null once measured
 */
public record ArchiveTextCheck(DocumentId documentId, int paperlessDocumentId, Integer recognizedCharacters,
        Boolean archiveVersion, Instant checkedAt, int failedAttempts, Instant nextAttemptAt) {

    /**
     * Fewer letters and digits than this count as almost no text. A one-page letter has several hundred; the rotated
     * and dark test photos gave none at all.
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
        if (archiveVersion != null && recognizedCharacters == null) {
            throw new IllegalArgumentException("the text and the archive version are read together");
        }
        if ((archiveVersion == null) == (nextAttemptAt == null)) {
            throw new IllegalArgumentException("a check is either measured or waiting for its next attempt");
        }
    }

    /**
     * Measures the text the archive recognized in its document {@code paperlessDocumentId} and records whether the
     * archive keeps a searchable archive version of it.
     */
    public static ArchiveTextCheck measured(DocumentId documentId, int paperlessDocumentId, String archiveText,
            boolean archiveVersion, Instant now) {
        return new ArchiveTextCheck(documentId, paperlessDocumentId, countRecognized(archiveText), archiveVersion,
                now, 0, null);
    }

    /**
     * Records that the archive document could not be read. Each further failure for the same archive document waits
     * twice as long before the next attempt, at most a day, so an archive that keeps refusing one document is asked
     * rarely. A measurement of this archive document stays as it is, and a count read before the archive version was
     * recorded is kept.
     */
    public static ArchiveTextCheck failed(DocumentId documentId, int paperlessDocumentId,
            Optional<ArchiveTextCheck> previous, Instant now) {
        Optional<ArchiveTextCheck> same = previous.filter(check -> check.appliesTo(paperlessDocumentId));
        if (same.filter(ArchiveTextCheck::isMeasured).isPresent()) {
            return same.get();
        }
        int attempts = same.map(ArchiveTextCheck::failedAttempts).orElse(0) + 1;
        Integer count = same.map(ArchiveTextCheck::recognizedCharacters).orElse(null);
        return new ArchiveTextCheck(documentId, paperlessDocumentId, count, null, now, attempts,
                now.plus(retryDelay(attempts)));
    }

    /** Whether this check belongs to the given archive document; a check of another one says nothing about it. */
    public boolean appliesTo(int paperlessDocumentId) {
        return this.paperlessDocumentId == paperlessDocumentId;
    }

    /** Whether the archive document was read completely: its text counted and its archive version recorded. */
    public boolean isMeasured() {
        return archiveVersion != null;
    }

    /** Whether the archive is known to keep a searchable archive version of the document. */
    public boolean keepsArchiveVersion() {
        return Boolean.TRUE.equals(archiveVersion);
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
