package dev.aegis.assistant.adapters.config;

import dev.aegis.assistant.application.AiSettings;
import dev.aegis.assistant.application.ModelPrice;
import dev.aegis.assistant.application.PriceBook;
import dev.aegis.assistant.application.WorkloadProfile;
import dev.aegis.assistant.domain.ModelSelection;
import dev.aegis.assistant.domain.Money;
import dev.aegis.assistant.domain.RunBudget;
import dev.aegis.assistant.domain.Workload;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Currency;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI configuration under {@code aegis.ai}: household limits, list prices per model and one profile per workload
 * (keys {@code connection-check}, {@code classification}, {@code review}, {@code chat}). Model names are
 * configuration, not code: a model can be replaced without a release.
 */
@ConfigurationProperties(prefix = "aegis.ai")
public record AiProperties(BigDecimal dailyCostLimit, String currency, Integer maxConcurrentRuns,
        Duration callTimeout, Map<String, Price> prices, Map<String, Profile> workloads) {

    /** List prices per million tokens in the configured currency. */
    public record Price(BigDecimal input, BigDecimal output, BigDecimal cacheRead, BigDecimal cacheWrite) {
    }

    public record Profile(String provider, String model, BigDecimal maxCost, Duration maxDuration,
            Integer maxModelCalls, Integer maxToolCalls, Integer maxOutputTokens, Integer maxAttempts,
            Integer maxConcurrent, String effort) {
    }

    public AiProperties {
        dailyCostLimit = dailyCostLimit == null ? new BigDecimal("2.00") : dailyCostLimit;
        currency = currency == null || currency.isBlank() ? "USD" : currency;
        maxConcurrentRuns = maxConcurrentRuns == null ? 2 : maxConcurrentRuns;
        callTimeout = callTimeout == null ? Duration.ofSeconds(120) : callTimeout;
        prices = prices == null ? Map.of() : Map.copyOf(prices);
        workloads = workloads == null ? Map.of() : Map.copyOf(workloads);
    }

    public AiSettings toSettings(ZoneId zone) {
        Currency unit = Currency.getInstance(currency);
        Map<String, ModelPrice> modelPrices = new LinkedHashMap<>();
        prices.forEach((model, price) -> modelPrices.put(model, new ModelPrice(money(price.input(), unit),
                money(price.output(), unit), money(price.cacheRead(), unit), money(price.cacheWrite(), unit))));
        Map<Workload, WorkloadProfile> profiles = new EnumMap<>(Workload.class);
        workloads.forEach((key, profile) -> {
            Workload workload = Workload.fromConfigKey(key);
            String provider = profile.provider() == null ? "anthropic" : profile.provider();
            ModelSelection model = profile.model() == null || profile.model().isBlank() ? null
                    : new ModelSelection(provider, profile.model());
            RunBudget budget = new RunBudget(require(profile.maxDuration(), key, "max-duration"),
                    new Money(require(profile.maxCost(), key, "max-cost"), unit),
                    require(profile.maxModelCalls(), key, "max-model-calls"),
                    profile.maxToolCalls() == null ? 0 : profile.maxToolCalls(),
                    require(profile.maxOutputTokens(), key, "max-output-tokens"),
                    require(profile.maxAttempts(), key, "max-attempts"));
            profiles.put(workload, new WorkloadProfile(workload, model, budget,
                    profile.maxConcurrent() == null ? 1 : profile.maxConcurrent(), profile.effort()));
        });
        return new AiSettings(profiles, new Money(dailyCostLimit, unit), maxConcurrentRuns, callTimeout, zone,
                new PriceBook(modelPrices));
    }

    private static Money money(BigDecimal amount, Currency unit) {
        return amount == null ? null : new Money(amount, unit);
    }

    private static <T> T require(T value, String workload, String property) {
        if (value == null) {
            throw new IllegalStateException("aegis.ai.workloads." + workload + "." + property + " is required");
        }
        return value;
    }
}
