package dev.aegis.documents.domain.bulk;

import dev.aegis.shared.InvalidInputException;
import java.util.UUID;

/**
 * Which part of the collection a bulk analysis covers (issue #115): the whole collection, the documents without a
 * folder, or one folder with or without its subfolders. Within the scope only documents that were never classified
 * are included.
 */
public record BulkScope(Kind kind, UUID folderId, boolean includeSubfolders) {
    public enum Kind {
        ALL,
        UNSORTED,
        FOLDER
    }

    public BulkScope {
        if (kind == null) {
            throw new InvalidInputException("analysis.bulk.scope", "A bulk analysis needs a scope");
        }
        if (kind == Kind.FOLDER && folderId == null) {
            throw new InvalidInputException("analysis.bulk.scope", "A folder scope needs a folder");
        }
        if (kind != Kind.FOLDER) {
            folderId = null;
            includeSubfolders = false;
        }
    }

    public static BulkScope all() {
        return new BulkScope(Kind.ALL, null, false);
    }

    public static BulkScope unsorted() {
        return new BulkScope(Kind.UNSORTED, null, false);
    }

    public static BulkScope folder(UUID folderId, boolean includeSubfolders) {
        return new BulkScope(Kind.FOLDER, folderId, includeSubfolders);
    }
}
