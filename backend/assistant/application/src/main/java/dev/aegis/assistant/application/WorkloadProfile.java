package dev.aegis.assistant.application;

import dev.aegis.assistant.domain.ModelSelection;
import dev.aegis.assistant.domain.RunBudget;
import dev.aegis.assistant.domain.Workload;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Configured model candidates and limits of one workload. {@code models} lists provider and model in order of
 * preference; a run uses the first candidate whose provider is usable when it is requested (for example the API key
 * first and the owner's subscription only without a key). The connection check has no models of its own: it checks
 * the models of the other profiles. {@code effort} is sent only when set, because not every model accepts it.
 */
public record WorkloadProfile(Workload workload, List<ModelSelection> models, RunBudget budget, int maxConcurrent,
        String effort) {
    public WorkloadProfile {
        Objects.requireNonNull(workload, "workload");
        Objects.requireNonNull(budget, "budget");
        models = models == null ? List.of() : List.copyOf(models);
        if (models.isEmpty() && workload != Workload.CONNECTION_CHECK) {
            throw new IllegalArgumentException("workload " + workload + " needs a provider and model");
        }
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("max concurrent runs must be positive");
        }
        effort = effort == null || effort.isBlank() ? null : effort.strip();
    }

    /** The most preferred candidate, shown when no candidate is usable. */
    public Optional<ModelSelection> preferred() {
        return models.stream().findFirst();
    }

    /** The first candidate in order of preference that {@code usable} accepts. */
    public Optional<ModelSelection> first(Predicate<ModelSelection> usable) {
        return models.stream().filter(usable).findFirst();
    }
}
