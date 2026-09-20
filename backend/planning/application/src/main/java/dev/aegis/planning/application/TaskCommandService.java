package dev.aegis.planning.application;

import dev.aegis.documents.application.api.DocumentReferenceQuery;
import dev.aegis.planning.application.api.ImportTask;
import dev.aegis.planning.application.api.TaskCommands;
import dev.aegis.planning.application.api.TaskExportRecord;
import dev.aegis.planning.application.api.TaskSummary;
import dev.aegis.planning.application.ports.PlanningEvents;
import dev.aegis.planning.domain.Task;
import dev.aegis.planning.domain.CancellationReview;
import dev.aegis.planning.domain.TaskRepository;
import dev.aegis.planning.domain.TaskSource;
import dev.aegis.planning.domain.TaskStatus;
import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.HouseholdAccess;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.NotFoundException;
import dev.aegis.shared.StaleVersionException;
import dev.aegis.shared.TaskId;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;

/** Task mutations; wrapped in a transaction by the composition root so events and state commit together. */
public class TaskCommandService implements TaskCommands, ImportTask {
    private final TaskRepository tasks;
    private final DocumentReferenceQuery documents;
    private final PlanningEvents events;
    private final TaskPresenter presenter;
    private final HouseholdAccess access;
    private final Clock clock;

    public TaskCommandService(TaskRepository tasks, DocumentReferenceQuery documents, PlanningEvents events,
            TaskPresenter presenter, HouseholdAccess access, Clock clock) {
        this.tasks = tasks;
        this.documents = documents;
        this.events = events;
        this.presenter = presenter;
        this.access = access;
        this.clock = clock;
    }

    @Override
    public TaskSummary create(Actor actor, CreateTaskCommand command) {
        access.requireMember(actor);
        requireOwner(command.ownerId());
        TaskSource source = null;
        if (command.sourceDocumentId() != null) {
            DocumentId documentId = command.sourceDocumentId();
            documents.findReference(documentId)
                    .orElseThrow(() -> new NotFoundException("document", documentId.toString()));
            source = new TaskSource(documentId);
        }
        Task task = Task.create(command.title(), command.note(), command.ownerId(), command.dueDate(),
                command.plannedDate(), source, actor.memberId(), clock.instant());
        tasks.save(task);
        notifyAssignment(task, actor);
        return presenter.summarize(reload(task.id()));
    }

    @Override
    public TaskSummary edit(Actor actor, TaskId id, String title, String note, long expectedVersion) {
        access.requireMember(actor);
        Task task = loadVersion(id, expectedVersion);
        task.edit(title, note, clock.instant());
        tasks.save(task);
        return presenter.summarize(reload(task.id()));
    }

    @Override
    public TaskSummary assign(Actor actor, TaskId id, MemberId ownerId, long expectedVersion) {
        access.requireMember(actor);
        requireOwner(ownerId);
        Task task = loadVersion(id, expectedVersion);
        boolean changed = !Objects.equals(task.owner().orElse(null), ownerId);
        task.assign(ownerId, clock.instant());
        tasks.save(task);
        if (changed) {
            notifyAssignment(task, actor);
        }
        return presenter.summarize(reload(task.id()));
    }

    @Override
    public TaskSummary planWork(Actor actor, TaskId id, LocalDate plannedDate, long expectedVersion) {
        access.requireMember(actor);
        Task task = loadVersion(id, expectedVersion);
        task.planWork(plannedDate, clock.instant());
        tasks.save(task);
        return presenter.summarize(reload(task.id()));
    }

    @Override
    public TaskSummary changeDueDate(Actor actor, TaskId id, LocalDate dueDate, long expectedVersion) {
        access.requireMember(actor);
        Task task = loadVersion(id, expectedVersion);
        task.changeDueDate(dueDate, clock.instant());
        tasks.save(task);
        return presenter.summarize(reload(task.id()));
    }

    @Override
    public TaskSummary complete(Actor actor, TaskId id, long expectedVersion) {
        access.requireMember(actor);
        Task task = loadVersion(id, expectedVersion);
        task.complete(actor.memberId(), clock.instant());
        tasks.save(task);
        task.owner().filter(owner -> !owner.equals(actor.memberId()))
                .ifPresent(owner -> events.taskCompleted(task.id(), task.title(), owner, actor.memberId()));
        return presenter.summarize(reload(task.id()));
    }

    @Override
    public TaskSummary reopen(Actor actor, TaskId id, long expectedVersion) {
        access.requireMember(actor);
        Task task = loadVersion(id, expectedVersion);
        task.reopen(clock.instant());
        tasks.save(task);
        return presenter.summarize(reload(task.id()));
    }

    @Override
    public ImportOutcome importTask(Actor actor, TaskExportRecord record) {
        access.requireMember(actor);
        if (tasks.findById(record.id()).isPresent()) {
            return ImportOutcome.ALREADY_PRESENT;
        }
        TaskStatus status;
        try {
            status = TaskStatus.valueOf(record.status());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidInputException("task.status.invalid", "Unknown task status " + record.status());
        }
        TaskSource source = record.sourceDocumentId() == null ? null : new TaskSource(record.sourceDocumentId());
        CancellationReview cancellation = record.contractId() == null ? null : new CancellationReview(
                record.contractId(), record.contractName(), record.contractEnd(), record.cancellationDeadline(),
                record.reminderDate(), record.contractVersion());
        Task task = Task.rehydrate(record.id(), record.title(), record.note(), record.ownerId(), status,
                record.dueDate(), record.plannedDate(), source, cancellation,
                record.createdBy() == null ? actor.memberId() : record.createdBy(),
                record.createdAt() == null ? clock.instant() : record.createdAt(), record.updatedAt(),
                record.completedAt(), record.completedBy(), 0);
        tasks.save(task);
        return ImportOutcome.IMPORTED;
    }

    private void notifyAssignment(Task task, Actor actor) {
        task.owner().filter(owner -> !owner.equals(actor.memberId()))
                .ifPresent(owner -> events.taskAssigned(task.id(), task.title(), owner, actor.memberId()));
    }

    private void requireOwner(MemberId ownerId) {
        if (ownerId == null) {
            return;
        }
        try {
            access.requireMember(new Actor(ownerId));
        } catch (dev.aegis.shared.NotAuthorizedException invalidOwner) {
            throw new InvalidInputException("validation.failed", "Task owner must be an active household member");
        }
    }

    /** Re-reads the aggregate so the returned version is the persisted one, not the pre-save value. */
    private Task reload(TaskId id) {
        return tasks.findById(id).orElseThrow(() -> new NotFoundException("task", id.toString()));
    }

    private Task loadVersion(TaskId id, long expectedVersion) {
        Task task = tasks.findById(id).orElseThrow(() -> new NotFoundException("task", id.toString()));
        if (task.version() != expectedVersion) {
            throw new StaleVersionException("task", id.toString(), expectedVersion, task.version());
        }
        return task;
    }
}
