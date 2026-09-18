package dev.aegis.assistant.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Most recent failure of a run. {@code code} is a stable machine-readable detail such as {@code budget.cost};
 * {@code message} is a bounded technical diagnostic that must never contain document text or credentials.
 */
public record RunFailure(FailureCategory category, String code, String message, Instant at) {
    public static final int MAX_MESSAGE = 500;

    public RunFailure {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(at, "at");
        message = sanitize(message);
    }

    static String sanitize(String message) {
        if (message == null) {
            return "";
        }
        String printable = message.replaceAll("\\p{Cntrl}", " ").strip();
        return printable.length() <= MAX_MESSAGE ? printable : printable.substring(0, MAX_MESSAGE - 3) + "...";
    }
}
