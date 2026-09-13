package dev.aegis.shared;

/** Raised when an actor is not allowed to run a use case. Mapped to HTTP 403 at the boundary. */
public class NotAuthorizedException extends RuntimeException {
    public NotAuthorizedException(String message) {
        super(message);
    }
}
