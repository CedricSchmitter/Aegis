package dev.aegis.documents.application.api;

import dev.aegis.shared.Actor;
import java.util.List;
import java.util.UUID;

/** Folder tree maintenance. Removing a folder never removes documents or originals. */
public interface CategoryCommands {

    /** {@code id} may be chosen by the client so that a retried request does not create a second folder. */
    record CreateCategory(UUID id, UUID parentId, String name) {
    }

    /**
     * Outcome of a deletion: documents moved to {@code contentsMovedTo} (null = Unsorted), subfolders moved up, and
     * folder-implied labels or subjects kept on documents that would otherwise have lost them.
     */
    record DeletionResult(UUID deletedId, UUID contentsMovedTo, int documentsMoved, int foldersMoved,
            int labelsKept, int subjectsKept) {
    }

    CategoryView create(Actor actor, CreateCategory command);

    CategoryView rename(Actor actor, UUID id, String name, long expectedVersion);

    /** {@code parentId} null moves the folder to the top level. */
    CategoryView move(Actor actor, UUID id, UUID parentId, long expectedVersion);

    CategoryView changeImplications(Actor actor, UUID id, List<UUID> labelIds, List<SubjectView> subjects,
            long expectedVersion);

    DeletionResult delete(Actor actor, UUID id, long expectedVersion);
}
