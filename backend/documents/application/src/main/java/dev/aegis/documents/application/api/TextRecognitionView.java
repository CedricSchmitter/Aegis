package dev.aegis.documents.application.api;

import java.time.Instant;

/**
 * Whether the archive recognized text in the document (issue #87, ADR-0012), derived from the archive's OCR text and
 * independent of AI. {@code state} is {@code NOT_ARCHIVED}, {@code PENDING} (archived, not checked yet),
 * {@code UNAVAILABLE} (the archive text could not be read; tried again at {@code nextAttemptAt}), {@code NONE},
 * {@code LITTLE} or {@code RECOGNIZED}. {@code recognizedCharacters} counts letters and digits once measured.
 */
public record TextRecognitionView(String state, Integer recognizedCharacters, Instant checkedAt,
        Instant nextAttemptAt) {
    public static final String NOT_ARCHIVED = "NOT_ARCHIVED";
    public static final String PENDING = "PENDING";
    public static final String UNAVAILABLE = "UNAVAILABLE";
    public static final String NONE = "NONE";
    public static final String LITTLE = "LITTLE";
    public static final String RECOGNIZED = "RECOGNIZED";

    /** Whether full-text search can hardly find the document because the archive recognized no or almost no text. */
    public boolean lacksText() {
        return NONE.equals(state) || LITTLE.equals(state);
    }
}
