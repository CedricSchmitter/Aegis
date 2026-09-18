package dev.aegis.assistant.application;

import dev.aegis.assistant.domain.Money;
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
 * Household-wide AI limits and the per-workload profiles. The daily cost limit counts every model call of the
 * household day in {@code zone}; all amounts share one currency.
 */
public record AiSettings(Map<Workload, WorkloadProfile> profiles, Money dailyCostLimit, int maxConcurrentRuns,
        Duration callTimeout, ZoneId zone, PriceBook prices) {
    public AiSettings {
        Objects.requireNonNull(dailyCostLimit, "daily cost limit");
        Objects.requireNonNull(callTimeout, "call timeout");
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(prices, "prices");
        profiles = profiles.isEmpty() ? Map.of() : Map.copyOf(new EnumMap<>(profiles));
        if (maxConcurrentRuns < 1) {
            throw new IllegalArgumentException("max concurrent runs must be positive");
        }
        Currency currency = dailyCostLimit.currency();
        for (WorkloadProfile profile : profiles.values()) {
            if (!profile.budget().maxCost().currency().equals(currency)) {
                throw new IllegalArgumentException("all AI budgets must use " + currency);
            }
        }
        for (ModelPrice price : prices.prices().values()) {
            if (!price.inputPerMillion().currency().equals(currency)) {
                throw new IllegalArgumentException("all AI prices must use " + currency);
            }
        }
    }

    public Optional<WorkloadProfile> profile(Workload workload) {
        return Optional.ofNullable(profiles.get(workload));
    }

    public Currency currency() {
        return dailyCostLimit.currency();
    }

    /** Start of the household day containing {@code now}. */
    public Instant startOfDay(Instant now) {
        return now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant();
    }
}
