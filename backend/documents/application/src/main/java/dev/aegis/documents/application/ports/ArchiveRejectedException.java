package dev.aegis.documents.application.ports;

/** The archive definitively rejected a request (authentication, validation); retrying will not help. */
public class ArchiveRejectedException extends RuntimeException {
    private final String code;

    public ArchiveRejectedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
