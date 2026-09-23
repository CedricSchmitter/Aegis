package dev.aegis.planning.application;

import dev.aegis.planning.application.api.TaskExportQuery;
import dev.aegis.planning.application.api.TaskExportRecord;
import dev.aegis.planning.application.api.TaskQueries;
import dev.aegis.planning.application.api.TaskSummary;
import dev.aegis.planning.application.ports.CalendarPort;
import dev.aegis.planning.domain.Task;
import dev.aegis.planning.domain.TaskRepository;
import dev.aegis.planning.domain.TodayPolicy;
import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.HouseholdAccess;
import dev.aegis.shared.NotFoundException;
import dev.aegis.shared.TaskId;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/** Read side of planning. Today is computed on demand from persisted tasks with an injected clock. */
public class TaskQueryService implements TaskQueries, TaskExportQuery {
    private final TaskRepository tasks;
    private final CalendarPort calendar;
    private final TaskPresenter presenter;
    private final HouseholdAccess access;
    private final Clock clock;

    public TaskQueryService(TaskRepository tasks, CalendarPort calendar, TaskPresenter presenter,
            HouseholdAccess access, Clock clock) {
        this.tasks = tasks;
        this.calendar = calendar;
        this.presenter = presenter;
        this.access = access;
        this.clock = clock;
    }

    @Override
    public TaskSummary get(Actor actor, TaskId id) {
        access.requireMember(actor);
        return presenter.summarize(tasks.findById(id).orElseThrow(() -> new NotFoundException("task",
                id.toString())));
    }

    @Override
    public List<TaskSummary> list(Actor actor, boolean includeDone) {
        access.requireMember(actor);
        List<Task> found = includeDone ? tasks.findAll() : tasks.findOpen();
        return found.stream().filter(task -> !task.isTrashed()).map(presenter::summarize).toList();
    }

    @Override
    public List<TaskSummary> forDocument(Actor actor, DocumentId documentId) {
        access.requireMember(actor);
        return tasks.findBySourceDocument(documentId).stream().filter(task -> !task.isTrashed())
                .map(presenter::summarize).toList();
    }

    @Override
    public List<TaskSummary> trashed(Actor actor) {
        access.requireMember(actor);
        return tasks.findTrashed().stream().map(presenter::summarize).toList();
    }

    @Override
    public TodayView today(Actor actor) {
        access.requireMember(actor);
        LocalDate today = LocalDate.now(clock);
        var buckets = TodayPolicy.build(tasks.findOpen(), today);
        var capabilities = calendar.capabilities();
        return new TodayView(today, summarize(buckets.overdue()), summarize(buckets.dueToday()),
                summarize(buckets.plannedToday()), summarize(buckets.upcoming()), summarize(buckets.unassigned()),
                new CalendarStatus(capabilities.connected(), capabilities.provider(), capabilities.readSupported(),
                        capabilities.writeSupported()));
    }

    @Override
    public List<TaskExportRecord> exportAll(Actor actor) {
        access.requireMember(actor);
        return tasks.findAll().stream().map(presenter::exportRecord).toList();
    }

    private List<TaskSummary> summarize(List<Task> list) {
        return list.stream().map(presenter::summarize).toList();
    }
}
