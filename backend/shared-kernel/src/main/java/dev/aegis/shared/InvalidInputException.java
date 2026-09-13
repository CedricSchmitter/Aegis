package dev.aegis.shared;

/**
 * Raised when a command violates a business rule. Carries a stable machine-readable code that the UI localizes.
 * Mapped to HTTP 400 at the boundary.
 */
public class InvalidInputException extends RuntimeException {
    private final String code;

    public InvalidInputException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
