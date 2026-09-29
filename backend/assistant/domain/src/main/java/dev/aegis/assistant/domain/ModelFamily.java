package dev.aegis.assistant.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * Claude model family a workload uses. The household chooses a family, not a version: each run uses the newest model
 * of the family that the provider offers (the API: the model of the server's catalog; the owner's subscription: the
 * Claude Code CLI's alias). Stored and exposed by name; the value is locale-independent.
 */
public enum ModelFamily {
    OPUS("opus"),
    SONNET("sonnet"),
    HAIKU("haiku");

    private final String key;

    ModelFamily(String key) {
        this.key = key;
    }

    /** Lower-case key used in configuration and as the Claude Code CLI alias of the family's newest model. */
    public String key() {
        return key;
    }

    public static ModelFamily fromKey(String key) {
        String normalized = key == null ? "" : key.strip().toLowerCase(Locale.ROOT);
        for (ModelFamily family : values()) {
            if (family.key.equals(normalized) || family.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return family;
            }
        }
        throw new IllegalArgumentException("unknown model family: " + key);
    }

    /** The family of a Claude model identifier such as {@code claude-sonnet-5-5}, if it names one. */
    public static Optional<ModelFamily> ofModel(String model) {
        if (model == null) {
            return Optional.empty();
        }
        String normalized = model.strip().toLowerCase(Locale.ROOT);
        for (ModelFamily family : values()) {
            String prefix = "claude-" + family.key;
            if (normalized.equals(prefix) || normalized.startsWith(prefix + "-")) {
                return Optional.of(family);
            }
        }
        return Optional.empty();
    }
}
