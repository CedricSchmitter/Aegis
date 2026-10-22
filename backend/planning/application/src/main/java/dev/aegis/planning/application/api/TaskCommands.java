package dev.aegis.planning.application.api;

import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.TaskId;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Task changes. Every mutation of an existing task carries the version the caller last saw; a mismatch raises
 * {@link dev.aegis.shared.StaleVersionException} instead of overwriting the other member's edit.
 */
public interface TaskCommands {

    record CreateTaskCommand(String title, String note, MemberId ownerId, LocalDate dueDate, LocalDate plannedDate,
            DocumentId sourceDocumentId) {
    }

    TaskSummary create(Actor actor, CreateTaskCommand command);

    TaskSummary edit(Actor actor, TaskId id, String title, String note, long expectedVersion);

    /** {@code ownerId} null makes the task explicitly unassigned. */
    TaskSummary assign(Actor actor, TaskId id, MemberId ownerId, long expectedVersion);

    /** Changes only the planned work date; the due date stays. */
    TaskSummary planWork(Actor actor, TaskId id, LocalDate plannedDate, long expectedVersion);

    TaskSummary changeDueDate(Actor actor, TaskId id, LocalDate dueDate, long expectedVersion);

    TaskSummary complete(Actor actor, TaskId id, long expectedVersion);

    TaskSummary reopen(Actor actor, TaskId id, long expectedVersion);

    /**
     * Moves the task to the trash (ADR-0010): it leaves lists, Today and reminders and keeps all of its data.
     * Repeating it for a trashed task returns it unchanged.
     */
    TaskSummary moveToTrash(Actor actor, TaskId id, long expectedVersion);

    /**
     * Brings a trashed task back. An owner who is no longer an active member is replaced by nobody. Repeating it for
     * a task that is not in the trash returns it unchanged.
     */
    TaskSummary restoreFromTrash(Actor actor, TaskId id, long expectedVersion);

    /**
     * Deletes a trashed task permanently (ADR-0021). An automatic cancellation review deleted this way is not created
     * again for the same deadline. Repeating it for a task that is already gone changes nothing.
     */
    void purge(Actor actor, TaskId id, long expectedVersion);

    /**
     * Reverses one recorded member edit (ADR-0029) with the task's ordinary operations and records the undo as a new
     * change. Refused with {@link dev.aegis.shared.UndoConflictException} when the change cannot be undone, was undone
     * already, or a later change replaced one of its fields; a later change to another field does not matter.
     */
    TaskSummary undo(Actor actor, TaskId id, UUID changeId, long expectedVersion);
}
