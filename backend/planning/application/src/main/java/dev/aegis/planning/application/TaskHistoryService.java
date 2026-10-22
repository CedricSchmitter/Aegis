package dev.aegis.planning.application;

import dev.aegis.planning.application.api.TaskHistoryQueries;
import dev.aegis.planning.domain.Task;
import dev.aegis.planning.domain.TaskRepository;
import dev.aegis.planning.domain.history.TaskChange;
import dev.aegis.planning.domain.history.TaskHistoryRepository;
import dev.aegis.planning.domain.history.TaskSnapshot;
import dev.aegis.shared.Actor;
import dev.aegis.shared.HouseholdAccess;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.NotFoundException;
import dev.aegis.shared.TaskId;
import java.util.List;

/** Reads a task's recorded history (ADR-0030) and says which changes can be undone right now. */
public class TaskHistoryService implements TaskHistoryQueries {
    public static final int MAX_PAGE = 100;

    private final TaskRepository tasks;
    private final TaskHistoryRepository history;
    private final HouseholdAccess access;

    public TaskHistoryService(TaskRepository tasks, TaskHistoryRepository history, HouseholdAccess access) {
        this.tasks = tasks;
        this.history = history;
        this.access = access;
    }

    @Override
    public TaskHistoryPage history(Actor actor, TaskId taskId, Long beforeVersion, int limit) {
        access.requireMember(actor);
        if (limit < 1 || limit > MAX_PAGE) {
            throw new InvalidInputException("validation.failed", "limit must be between 1 and " + MAX_PAGE);
        }
        Task task = tasks.findById(taskId).orElseThrow(() -> new NotFoundException("task", taskId.toString()));
        TaskSnapshot current = TaskSnapshot.of(task);
        List<TaskChange> page = history.findByTask(taskId, beforeVersion, limit + 1);
        boolean more = page.size() > limit;
        List<TaskChangeView> views = page.stream().limit(limit).map(change -> view(change, task, current)).toList();
        Long next = more ? views.getLast().taskVersion() : null;
        return new TaskHistoryPage(taskId, history.recordingSince(), views, next);
    }

    private TaskChangeView view(TaskChange change, Task task, TaskSnapshot current) {
        var undoneBy = change.undoable() ? history.undoneBy(change.id()).orElse(null) : null;
        boolean undoable = change.undoable() && undoneBy == null && !task.isTrashed()
                && change.overwrittenIn(current).isEmpty();
        return new TaskChangeView(change.id(), change.taskVersion(), change.occurredAt(), change.actor().kind().name(),
                change.actor().member(), change.actor().reference(), change.action().name(), change.changes(),
                change.undoOf(), undoneBy, undoable);
    }
}
