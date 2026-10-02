package dev.aegis.assistant.domain;

import java.util.Locale;

/**
 * Kinds of AI work, each with its own provider, model and budget profile. Stored and exposed by name; the value is
 * locale-independent. Only workloads with a registered handler can be requested.
 */
public enum Workload {
    /** Minimal structured call that proves credentials, model availability and structured output. */
    CONNECTION_CHECK("connection-check"),
    /** Lightweight classification of uploaded documents. */
    DOCUMENT_CLASSIFICATION("classification"),
    /** Scheduled review of new or changed documents by a stronger model. */
    DOCUMENT_REVIEW("review"),
    /** Source-grounded questions over household data. */
    CHAT("chat");

    private final String configKey;

    Workload(String configKey) {
        this.configKey = configKey;
    }

    /** Key of the workload profile in the application configuration. */
    public String configKey() {
        return configKey;
    }

    public static Workload fromConfigKey(String key) {
        String normalized = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        for (Workload workload : values()) {
            if (workload.configKey.equals(normalized)) {
                return workload;
            }
        }
        throw new IllegalArgumentException("unknown AI workload: " + key);
    }
}
