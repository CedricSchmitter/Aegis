package dev.aegis.documents.application.api;

/** The archive's text of a document cannot be read right now (archive unreachable); retrying later can succeed. */
public class ContentUnavailableException extends RuntimeException {
    public ContentUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
