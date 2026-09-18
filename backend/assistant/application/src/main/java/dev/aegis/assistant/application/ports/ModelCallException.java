package dev.aegis.assistant.application.ports;

import dev.aegis.assistant.domain.FailureCategory;
import java.time.Duration;
import java.util.Objects;

/**
 * A failed model call, classified by the gateway. {@code code} is a stable detail such as
 * {@code provider.rateLimited}; {@code retryAfter} is the provider's hint, if any. The message must not contain
 * credentials or prompt content.
 */
public class ModelCallException extends RuntimeException {
    private final FailureCategory category;
    private final String code;
    private final Duration retryAfter;

    public ModelCallException(FailureCategory category, String code, String message) {
        this(category, code, message, null, null);
    }

    public ModelCallException(FailureCategory category, String code, String message, Duration retryAfter,
            Throwable cause) {
        super(message, cause);
        this.category = Objects.requireNonNull(category, "category");
        this.code = Objects.requireNonNull(code, "code");
        this.retryAfter = retryAfter;
    }

    public FailureCategory category() {
        return category;
    }

    public String code() {
        return code;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
