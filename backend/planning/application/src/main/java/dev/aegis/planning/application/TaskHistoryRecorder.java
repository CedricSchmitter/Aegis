package dev.aegis.planning.application;

import dev.aegis.planning.domain.Task;
import dev.aegis.planning.domain.TaskRepository;
import dev.aegis.planning.domain.history.TaskAction;
import dev.aegis.planning.domain.history.TaskChange;
import dev.aegis.planning.domain.history.TaskHistoryRepository;
import dev.aegis.planning.domain.history.TaskSnapshot;
import dev.aegis.shared.ChangeActor;
import dev.aegis.shared.FieldChange;
import dev.aegis.shared.NotFoundException;
import dev.aegis.shared.TaskId;
import java.time.Clock;
import java.util.List;

/**
 * Records a saved task change in the history (ADR-0029). Called inside the transaction of the change, after the save,
 * so the entry carries the persisted version and commits or rolls back with it.
 */
final class TaskHistoryRecorder {
    private final TaskRepository tasks;
    private final TaskHistoryRepository history;
    private final Clock clock;

    TaskHistoryRecorder(TaskRepository tasks, TaskHistoryRepository history, Clock clock) {
        this.tasks = tasks;
        this.history = history;
        this.clock = clock;
    }

    /**
     * Records what changed since {@code before} and returns the saved task. A field edit that changed nothing is not
     * recorded; creation, the trash and automatic work are recorded even without a field change.
     */
    Task record(TaskSnapshot before, TaskId id, ChangeActor actor, TaskAction action) {
        Task saved = reload(id);
        List<FieldChange> changes = TaskSnapshot.of(saved).changesSince(before);
        if (!changes.isEmpty() || !action.undoable()) {
            history.append(TaskChange.record(id, saved.version(), clock.instant(), actor, action, changes));
        }
        return saved;
    }

    Task recordUndo(TaskChange undone, TaskSnapshot before, ChangeActor actor) {
        Task saved = reload(undone.taskId());
        history.append(TaskChange.undo(undone, saved.version(), clock.instant(), actor,
                TaskSnapshot.of(saved).changesSince(before)));
        return saved;
    }

    private Task reload(TaskId id) {
        return tasks.findById(id).orElseThrow(() -> new NotFoundException("task", id.toString()));
    }
}
