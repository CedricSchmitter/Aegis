package dev.aegis.assistant.application;

import dev.aegis.assistant.domain.ModelSelection;
import dev.aegis.assistant.domain.RunBudget;
import dev.aegis.assistant.domain.Workload;
import java.util.Objects;
import java.util.Optional;

/**
 * Configured provider, model and limits of one workload. The connection check has no model of its own: it checks
 * the models of the other profiles. {@code effort} is sent only when set, because not every model accepts it.
 */
public record WorkloadProfile(Workload workload, ModelSelection model, RunBudget budget, int maxConcurrent,
        String effort) {
    public WorkloadProfile {
        Objects.requireNonNull(workload, "workload");
        Objects.requireNonNull(budget, "budget");
        if (model == null && workload != Workload.CONNECTION_CHECK) {
            throw new IllegalArgumentException("workload " + workload + " needs a provider and model");
        }
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("max concurrent runs must be positive");
        }
        effort = effort == null || effort.isBlank() ? null : effort.strip();
    }

    public Optional<ModelSelection> modelSelection() {
        return Optional.ofNullable(model);
    }
}
