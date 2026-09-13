package dev.aegis.documents.application.ports;

/** The archive could not be reached or answered with a transient error; the operation may be retried. */
public class ArchiveUnavailableException extends RuntimeException {
    public ArchiveUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public ArchiveUnavailableException(String message) {
        super(message);
    }
}
