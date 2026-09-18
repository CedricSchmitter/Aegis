package dev.aegis.assistant.domain;

import java.util.Objects;

/** Provider and model identifier taken from the workload profile when the run was requested. */
public record ModelSelection(String provider, String model) {
    public ModelSelection {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(model, "model");
        if (provider.isBlank() || model.isBlank()) {
            throw new IllegalArgumentException("provider and model must not be blank");
        }
    }
}
