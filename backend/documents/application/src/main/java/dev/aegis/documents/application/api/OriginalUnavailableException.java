package dev.aegis.documents.application.api;

/** The original exists but cannot be served now (archive unreachable and staging copy already removed). */
public class OriginalUnavailableException extends RuntimeException {
    public OriginalUnavailableException(String message) {
        super(message);
    }
}
