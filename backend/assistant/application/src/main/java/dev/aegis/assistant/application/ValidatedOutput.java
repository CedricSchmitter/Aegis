package dev.aegis.assistant.application;

import java.util.Objects;

/**
 * Model output that passed the workload's validation. {@code json} is stored as the visible result; {@code value} is
 * the typed result that the handler applies.
 */
public record ValidatedOutput(String json, Object value) {
    public ValidatedOutput {
        Objects.requireNonNull(json, "json");
    }
}
