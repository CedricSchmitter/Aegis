package dev.aegis.planning.domain.history;

import dev.aegis.shared.ChangeActor;
import dev.aegis.shared.FieldChange;
import dev.aegis.shared.TaskId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One recorded change of a task (ADR-0030). {@code taskVersion} is the task's version after the change, so one saved
 * state is recorded at most once. {@code undoOf} names the change an undo reversed.
 */
public record TaskChange(UUID id, TaskId taskId, long taskVersion, Instant occurredAt, ChangeActor actor,
        TaskAction action, List<FieldChange> changes, UUID undoOf) {

    public TaskChange {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(taskId, "task");
        Objects.requireNonNull(occurredAt, "occurred at");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(action, "action");
        changes = List.copyOf(changes);
        if ((action == TaskAction.UNDONE) != (undoOf != null)) {
            throw new IllegalArgumentException("only an undo names the change it reversed");
        }
    }

    public static TaskChange record(TaskId taskId, long taskVersion, Instant now, ChangeActor actor,
            TaskAction action, List<FieldChange> changes) {
        return new TaskChange(UUID.randomUUID(), taskId, taskVersion, now, actor, action, changes, null);
    }

    public static TaskChange undo(TaskChange undone, long taskVersion, Instant now, ChangeActor actor,
            List<FieldChange> changes) {
        return new TaskChange(UUID.randomUUID(), undone.taskId, taskVersion, now, actor, TaskAction.UNDONE,
                changes, undone.id);
    }

    /** A member's own field edit that still has something to reverse. */
    public boolean undoable() {
        return actor.kind() == ChangeActor.Kind.MEMBER && action.undoable() && !changes.isEmpty();
    }

    /**
     * The fields whose current value is no longer the one this change left. An undo would overwrite a later edit
     * there, so it is refused; changes to other fields do not matter.
     */
    public List<String> overwrittenIn(TaskSnapshot current) {
        return changes.stream().filter(c -> !Objects.equals(current.value(TaskField.ofCode(c.field())), c.after()))
                .map(FieldChange::field).toList();
    }
}
