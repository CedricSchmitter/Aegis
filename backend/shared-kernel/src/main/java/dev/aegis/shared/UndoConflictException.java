package dev.aegis.shared;

import java.util.HashMap;
import java.util.Map;

/**
 * Raised when a recorded change cannot be undone (ADR-0029): it is not an undoable kind of change, it was already
 * undone, or a later change touched the same field, which an undo must never overwrite. Mapped to HTTP 409 with the
 * code {@code history.undo.<reason>}.
 */
public class UndoConflictException extends InvalidInputException {

    public UndoConflictException(String reason, String changeId, String message, Map<String, String> details) {
        super("history.undo." + reason, message, withChange(changeId, details));
    }

    public static UndoConflictException notSupported(String changeId) {
        return new UndoConflictException("notSupported", changeId, "change " + changeId + " cannot be undone",
                Map.of());
    }

    public static UndoConflictException alreadyUndone(String changeId) {
        return new UndoConflictException("alreadyUndone", changeId, "change " + changeId + " was already undone",
                Map.of());
    }

    /** A later change replaced the value; {@code fields} lists the field codes, comma-separated. */
    public static UndoConflictException changedSince(String changeId, String fields) {
        return new UndoConflictException("changedSince", changeId,
                "change " + changeId + " was overwritten since: " + fields, Map.of("fields", fields));
    }

    private static Map<String, String> withChange(String changeId, Map<String, String> details) {
        Map<String, String> all = new HashMap<>(details);
        all.put("changeId", changeId);
        return all;
    }
}
