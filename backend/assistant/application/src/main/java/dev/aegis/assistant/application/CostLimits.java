package dev.aegis.assistant.application;

import dev.aegis.assistant.domain.AiRunRepository;
import dev.aegis.assistant.domain.Money;
import dev.aegis.assistant.domain.RunBudget;
import dev.aegis.assistant.domain.Workload;
import dev.aegis.assistant.domain.WorkloadCostLimits;
import dev.aegis.assistant.domain.WorkloadCostLimitsRepository;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The cost limits that apply to runs: the household's own limits of a workload once a member set them, otherwise the
 * configured defaults. A workload with its own daily limit counts only its own model calls per provider and household
 * day; the workloads without one share the provider's configured daily limit and count their calls together, so
 * spending under an own limit never uses up the shared one. An own run limit replaces the run cost budget of the
 * profile and of the provider. Limits stored in another currency than the configured one are ignored.
 */
final class CostLimits {
    private final AiSettings settings;
    private final WorkloadCostLimitsRepository limits;
    private final AiRunRepository runs;

    /** A daily limit with what its runs spent today; {@code own} tells whether it is the workload's own limit. */
    record Daily(Money limit, Money spent, boolean own) {
    }

    CostLimits(AiSettings settings, WorkloadCostLimitsRepository limits, AiRunRepository runs) {
        this.settings = settings;
        this.limits = limits;
        this.runs = runs;
    }

    /** The limits as stored now; read once per use case so all its decisions see the same limits. */
    Current current() {
        Map<Workload, WorkloadCostLimits> own = new EnumMap<>(Workload.class);
        limits.findAll().forEach((workload, stored) -> {
            if (stored.own() && stored.dailyLimit().orElseThrow().currency().equals(settings.currency())) {
                own.put(workload, stored);
            }
        });
        return new Current(own);
    }

    final class Current {
        private final Map<Workload, WorkloadCostLimits> own;

        private Current(Map<Workload, WorkloadCostLimits> own) {
            this.own = own;
        }

        /** The household's own limits of {@code workload}, if a member set them. */
        Optional<WorkloadCostLimits> own(Workload workload) {
            return Optional.ofNullable(own.get(workload));
        }

        /** Budget of a new run of {@code profile} on {@code provider}. */
        RunBudget runBudget(WorkloadProfile profile, String provider) {
            RunBudget budget = settings.budget(profile, provider);
            return own(profile.workload()).flatMap(WorkloadCostLimits::runLimit).map(budget::withMaxCost)
                    .orElse(budget);
        }

        /** Daily limit of a run of {@code workload} on {@code provider} with today's spending under it. */
        Daily daily(Workload workload, String provider, Instant now) {
            Optional<Money> limit = own(workload).flatMap(WorkloadCostLimits::dailyLimit);
            if (limit.isEmpty()) {
                return shared(provider, now);
            }
            return new Daily(limit.get(), spent(provider, EnumSet.of(workload), now), true);
        }

        /** The provider's configured daily limit that the workloads without an own daily limit share. */
        Daily shared(String provider, Instant now) {
            Set<Workload> sharing = EnumSet.allOf(Workload.class);
            sharing.removeAll(own.keySet());
            return new Daily(settings.dailyCostLimit(provider), spent(provider, sharing, now), false);
        }

        private Money spent(String provider, Set<Workload> workloads, Instant now) {
            return provider == null ? Money.zero(settings.currency())
                    : runs.costSince(settings.startOfDay(now), provider, workloads, settings.currency());
        }
    }
}
