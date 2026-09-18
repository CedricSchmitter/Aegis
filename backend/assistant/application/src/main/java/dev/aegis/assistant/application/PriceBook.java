package dev.aegis.assistant.application;

import java.util.Map;
import java.util.Optional;

/**
 * Configured prices by model identifier. A model without a price cannot run, because its cost budget could not be
 * enforced.
 */
public record PriceBook(Map<String, ModelPrice> prices) {
    public PriceBook {
        prices = Map.copyOf(prices);
    }

    public Optional<ModelPrice> find(String model) {
        return Optional.ofNullable(prices.get(model));
    }
}
