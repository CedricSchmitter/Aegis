package dev.aegis.shared;

import java.util.Map;

/**
 * Raised when permanent deletion (ADR-0021) conflicts with a record's current state: it is not in the
 * trash, its archival has not settled yet, or its permanent deletion is already under way and it can no longer be
 * restored. Mapped to HTTP 409 with the code {@code <resource>.purge.<reason>}.
 */
public class PurgeConflictException extends InvalidInputException {

    public PurgeConflictException(String resource, String id, String reason, String message) {
        super(resource + ".purge." + reason, message, Map.of("id", id));
    }

    /** Permanent deletion applies to the trash only; a record outside it is moved to the trash first. */
    public static PurgeConflictException notTrashed(String resource, String id) {
        return new PurgeConflictException(resource, id, "notTrashed",
                resource + " " + id + " is not in the trash");
    }
}
