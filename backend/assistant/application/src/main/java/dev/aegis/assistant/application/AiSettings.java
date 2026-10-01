package dev.aegis.assistant.application;

import dev.aegis.assistant.domain.Money;
import dev.aegis.assistant.domain.RunBudget;
import dev.aegis.assistant.domain.Workload;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Currency;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Household-wide AI limits and the per-workload profiles. Daily cost limits count the model calls of one provider
 * per household day in {@code zone}: {@code dailyCostLimit} is the default, {@code providerLimits} can set a
 * provider's own daily limit and run budget. All amounts share one currency. Every model of the {@code catalog} needs
 * a price, and every workload's default family must be in the catalog. A run that finds no free slot under
 * {@code maxConcurrentRuns} or its workload's limit checks again after {@code slotWait}.
 */
public record AiSettings(Map<Workload, WorkloadProfile> profiles, Money dailyCostLimit, int maxConcurrentRuns,
        Duration callTimeout, Duration slotWait, ZoneId zone, PriceBook prices, Map<String, ProviderLimits> providerLimits,
        ModelCatalog catalog) {
    public AiSettings {
        Objects.requireNonNull(dailyCostLimit, "daily cost limit");
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(callTimeout, "call timeout");
        Objects.requireNonNull(slotWait, "slot wait");
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(prices, "prices");
        profiles = profiles.isEmpty() ? Map.of() : Map.copyOf(new EnumMap<>(profiles));
        providerLimits = providerLimits == null ? Map.of() : Map.copyOf(providerLimits);
        if (maxConcurrentRuns < 1) {
            throw new IllegalArgumentException("max concurrent runs must be positive");
        }
        if (!slotWait.isPositive()) {
            throw new IllegalArgumentException("slot wait must be positive");
        }
        Currency currency = dailyCostLimit.currency();
        for (WorkloadProfile profile : profiles.values()) {
            if (!profile.budget().maxCost().currency().equals(currency)) {
                throw new IllegalArgumentException("all AI budgets must use " + currency);
            }
        }
        for (ProviderLimits limits : providerLimits.values()) {
            for (Money amount : new Money[] {limits.dailyCostLimit(), limits.maxRunCost()}) {
                if (amount != null && !amount.currency().equals(currency)) {
                    throw new IllegalArgumentException("all AI limits must use " + currency);
                }
            }
        }
        for (ModelPrice price : prices.prices().values()) {
            if (!price.inputPerMillion().currency().equals(currency)) {
                throw new IllegalArgumentException("all AI prices must use " + currency);
            }
        }
        for (ModelCatalog.Entry entry : catalog.entries().values()) {
            if (prices.find(entry.model()).isEmpty()) {
                throw new IllegalArgumentException("the catalog model " + entry.model() + " has no price");
            }
        }
        for (WorkloadProfile profile : profiles.values()) {
            if (profile.family() != null && catalog.entry(profile.family()).isEmpty()) {
                throw new IllegalArgumentException("the default family " + profile.family().key() + " of "
                        + profile.workload() + " is not in the model catalog");
            }
        }
    }

    public Optional<WorkloadProfile> profile(Workload workload) {
        return Optional.ofNullable(profiles.get(workload));
    }

    /** Daily cost limit of {@code provider}: its own limit if configured, otherwise the household default. */
    public Money dailyCostLimit(String provider) {
        ProviderLimits limits = provider == null ? null : providerLimits.get(provider);
        return limits != null && limits.dailyCostLimit() != null ? limits.dailyCostLimit() : dailyCostLimit;
    }

    /** Budget of a run of {@code profile} on {@code provider}: the profile's, with the provider's run cost if set. */
    public RunBudget budget(WorkloadProfile profile, String provider) {
        RunBudget budget = profile.budget();
        ProviderLimits limits = providerLimits.get(provider);
        if (limits == null || limits.maxRunCost() == null) {
            return budget;
        }
        return new RunBudget(budget.maxDuration(), limits.maxRunCost(), budget.maxModelCalls(), budget.maxToolCalls(),
                budget.maxOutputTokens(), budget.maxAttempts());
    }

    public Currency currency() {
        return dailyCostLimit.currency();
    }

    /** Start of the household day containing {@code now}. */
    public Instant startOfDay(Instant now) {
        return now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant();
    }

    /** Start of the household day after the one containing {@code now}, when the daily limits renew. */
    public Instant startOfNextDay(Instant now) {
        return now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant();
    }
}
