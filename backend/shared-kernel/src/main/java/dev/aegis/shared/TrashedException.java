package dev.aegis.shared;

import java.util.Map;

/**
 * Raised when a command would change a record that is in the trash. The member restores it first; nothing in a
 * trashed record changes behind the household's back. Mapped to HTTP 409 with the code {@code <resource>.trashed}.
 */
public class TrashedException extends InvalidInputException {

    public TrashedException(String resource, String id) {
        super(resource + ".trashed", resource + " " + id + " is in the trash", Map.of("id", id));
    }
}
