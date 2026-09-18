package dev.aegis.documents.domain.classification;

import dev.aegis.shared.InvalidInputException;

/** Confidence reported by a classifier, between 0 and 1. Shown as reported; not a calibrated guarantee. */
public record Confidence(double value) {
    public Confidence {
        if (Double.isNaN(value) || value < 0 || value > 1) {
            throw new InvalidInputException("classification.confidence.invalid", "Confidence must be within 0..1");
        }
    }
}
