package dev.aegis.shared;

import java.util.Map;

/**
 * Raised when a command violates a business rule. Carries a stable machine-readable code that the UI localizes and
 * optional machine-readable details (for example the id of an existing entry that caused a conflict). Mapped to
 * HTTP 400 at the boundary.
 */
public class InvalidInputException extends RuntimeException {
    private final String code;
    private final Map<String, String> details;

    public InvalidInputException(String code, String message) {
        this(code, message, Map.of());
    }

    public InvalidInputException(String code, String message, Map<String, String> details) {
        super(message);
        this.code = code;
        this.details = Map.copyOf(details);
    }

    public String code() {
        return code;
    }

    public Map<String, String> details() {
        return details;
    }
}
