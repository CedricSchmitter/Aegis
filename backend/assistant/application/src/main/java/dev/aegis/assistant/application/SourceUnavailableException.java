package dev.aegis.assistant.application;

/** A handler could not read an input because its source system is temporarily unreachable; the attempt is retried. */
public class SourceUnavailableException extends RuntimeException {
    public SourceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
