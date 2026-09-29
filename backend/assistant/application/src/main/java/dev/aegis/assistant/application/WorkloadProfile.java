package dev.aegis.assistant.application;

import dev.aegis.assistant.domain.ModelFamily;
import dev.aegis.assistant.domain.RunBudget;
import dev.aegis.assistant.domain.Workload;
import java.util.List;
import java.util.Objects;

/**
 * Configured providers and limits of one workload. {@code providers} lists the providers in order of preference; a
 * run uses the first one that is usable when it is requested (for example the API key first and the owner's
 * subscription only without a key). {@code family} is the default model family until the household chooses another
 * one. The connection check has neither: it checks the models of the other profiles. {@code effort} is sent only when
 * set and only to models that accept it.
 */
public record WorkloadProfile(Workload workload, List<String> providers, ModelFamily family, RunBudget budget,
        int maxConcurrent, String effort) {
    public WorkloadProfile {
        Objects.requireNonNull(workload, "workload");
        Objects.requireNonNull(budget, "budget");
        providers = providers == null ? List.of()
                : providers.stream().map(String::strip).filter(provider -> !provider.isEmpty()).distinct().toList();
        if (workload != Workload.CONNECTION_CHECK && (providers.isEmpty() || family == null)) {
            throw new IllegalArgumentException("workload " + workload + " needs a provider and a model family");
        }
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("max concurrent runs must be positive");
        }
        effort = effort == null || effort.isBlank() ? null : effort.strip();
    }
}
