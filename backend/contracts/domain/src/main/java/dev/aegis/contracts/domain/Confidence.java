package dev.aegis.contracts.domain;

import dev.aegis.shared.InvalidInputException;

/** Confidence an extraction run reported, between 0 and 1. Shown as reported; never a calibrated guarantee. */
public record Confidence(double value) {
    public Confidence {
        if (Double.isNaN(value) || value < 0 || value > 1) {
            throw new InvalidInputException("contract.confidence.invalid", "Confidence must be within 0..1");
        }
    }
}
