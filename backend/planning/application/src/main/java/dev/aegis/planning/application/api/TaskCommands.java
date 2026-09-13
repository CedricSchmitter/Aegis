package dev.aegis.planning.application.api;

import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.TaskId;
import java.time.LocalDate;

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
}
