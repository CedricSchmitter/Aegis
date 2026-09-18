package dev.aegis.assistant.application;

/** The model's answer is truncated, malformed or violates the workload contract; the attempt fails as such. */
public class InvalidModelOutputException extends RuntimeException {
    private final String code;

    public InvalidModelOutputException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
