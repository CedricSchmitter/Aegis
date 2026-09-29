package dev.aegis.assistant.application;

import dev.aegis.assistant.domain.ModelFamily;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Server-controlled models the household can choose from: per family the newest model that the API serves with a
 * known price. Members choose a family, never a model identifier, price or endpoint. When a new model of a family is
 * released, its identifier and price are updated in the configuration; the owner's subscription follows the Claude
 * Code CLI's alias of the family without any change.
 */
public record ModelCatalog(Map<ModelFamily, Entry> entries) {
    /** {@code effort} tells whether the model accepts an effort level; it is not sent to models without one. */
    public record Entry(ModelFamily family, String model, boolean effort) {
        public Entry {
            Objects.requireNonNull(family, "family");
            Objects.requireNonNull(model, "model");
            if (model.isBlank()) {
                throw new IllegalArgumentException("the model of " + family + " must not be blank");
            }
            if (ModelFamily.ofModel(model).filter(family::equals).isEmpty()) {
                throw new IllegalArgumentException(model + " is not a model of the " + family.key() + " family");
            }
        }
    }

    public ModelCatalog {
        entries = entries.isEmpty() ? Map.of() : Map.copyOf(new EnumMap<>(entries));
        entries.forEach((family, entry) -> {
            if (entry.family() != family) {
                throw new IllegalArgumentException("catalog entry " + entry.model() + " is filed under " + family);
            }
        });
    }

    public Optional<Entry> entry(ModelFamily family) {
        return Optional.ofNullable(entries.get(family));
    }

    /** Offered families in a stable order (Opus, Sonnet, Haiku). */
    public List<Entry> offered() {
        return entries.values().stream().sorted((a, b) -> a.family().compareTo(b.family())).toList();
    }

    /**
     * Whether {@code model} accepts an effort level. Models outside the catalog, for example of runs requested before
     * a catalog update, keep the behavior they were requested with.
     */
    public boolean acceptsEffort(String model) {
        return entries.values().stream().filter(entry -> entry.model().equals(model)).findFirst()
                .map(Entry::effort).orElse(true);
    }
}
