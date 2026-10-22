package dev.aegis.planning.application;

import dev.aegis.documents.application.api.DocumentReferenceQuery;
import dev.aegis.documents.application.api.DocumentReference;
import dev.aegis.planning.application.api.ImportTask;
import dev.aegis.planning.application.api.CancellationDeadlineReconciliation;
import dev.aegis.planning.application.api.TaskCommands;
import dev.aegis.planning.application.api.TaskExportRecord;
import dev.aegis.planning.application.api.TaskSummary;
import dev.aegis.planning.application.ports.PlanningEvents;
import dev.aegis.planning.domain.Task;
import dev.aegis.planning.domain.CancellationReview;
import dev.aegis.planning.domain.TaskRepository;
import dev.aegis.planning.domain.TaskSource;
import dev.aegis.planning.domain.TaskStatus;
import dev.aegis.planning.domain.history.TaskAction;
import dev.aegis.planning.domain.history.TaskChange;
import dev.aegis.planning.domain.history.TaskField;
import dev.aegis.planning.domain.history.TaskHistoryRepository;
import dev.aegis.planning.domain.history.TaskSnapshot;
import dev.aegis.shared.Actor;
import dev.aegis.shared.ChangeActor;
import dev.aegis.shared.FieldChange;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.HouseholdAccess;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.NotFoundException;
import dev.aegis.shared.PurgeConflictException;
import dev.aegis.shared.StaleVersionException;
import dev.aegis.shared.TaskId;
import dev.aegis.shared.Trash;
import dev.aegis.shared.TrashedException;
import dev.aegis.shared.UndoConflictException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Task mutations; wrapped in a transaction by the composition root so events and state commit together. */
public class TaskCommandService implements TaskCommands, ImportTask {
    private final TaskRepository tasks;
    private final TaskHistoryRepository history;
    private final TaskHistoryRecorder recorder;
    private final DocumentReferenceQuery documents;
    private final PlanningEvents events;
    private final TaskPresenter presenter;
    private final HouseholdAccess access;
    private final CancellationDeadlineReconciliation cancellations;
    private final Trash.RecoveryWindow recoveryWindow;
    private final Clock clock;

    public TaskCommandService(TaskRepository tasks, TaskHistoryRepository history, DocumentReferenceQuery documents,
            PlanningEvents events, TaskPresenter presenter, HouseholdAccess access,
            CancellationDeadlineReconciliation cancellations, Trash.RecoveryWindow recoveryWindow, Clock clock) {
        this.tasks = tasks;
        this.history = history;
        this.recorder = new TaskHistoryRecorder(tasks, history, clock);
        this.documents = documents;
        this.events = events;
        this.presenter = presenter;
        this.access = access;
        this.cancellations = cancellations;
        this.recoveryWindow = recoveryWindow;
        this.clock = clock;
    }

    @Override
    public TaskSummary create(Actor actor, CreateTaskCommand command) {
        access.requireMember(actor);
        requireOwner(command.ownerId());
        TaskSource source = null;
        if (command.sourceDocumentId() != null) {
            DocumentId documentId = command.sourceDocumentId();
            DocumentReference reference = documents.findReference(documentId)
                    .orElseThrow(() -> new NotFoundException("document", documentId.toString()));
            if (reference.trashed()) {
                throw new TrashedException("document", documentId.toString());
            }
            source = new TaskSource(documentId);
        }
        Task task = Task.create(command.title(), command.note(), command.ownerId(), command.dueDate(),
                command.plannedDate(), source, actor.memberId(), clock.instant());
        tasks.save(task);
        notifyAssignment(task, actor);
        return presenter.summarize(recorder.record(TaskSnapshot.NONE, task.id(), ChangeActor.of(actor),
                TaskAction.CREATED));
    }

    @Override
    public TaskSummary edit(Actor actor, TaskId id, String title, String note, long expectedVersion) {
        access.requireMember(actor);
        Task task = loadVersion(id, expectedVersion);
        TaskSnapshot before = TaskSnapshot.of(task);
        task.edit(title, note, clock.instant());
        tasks.save(task);
        return presenter.summarize(recorder.record(before, id, ChangeActor.of(actor), TaskAction.EDITED));
    }

    @Override
    public TaskSummary assign(Actor actor, TaskId id, MemberId ownerId, long expectedVersion) {
        access.requireMember(actor);
        requireOwner(ownerId);
        Task task = loadVersion(id, expectedVersion);
        TaskSnapshot before = TaskSnapshot.of(task);
        boolean changed = !Objects.equals(task.owner().orElse(null), ownerId);
        task.assign(ownerId, clock.instant());
        tasks.save(task);
        if (changed) {
            notifyAssignment(task, actor);
        }
        return presenter.summarize(recorder.record(before, id, ChangeActor.of(actor), TaskAction.ASSIGNED));
    }

    @Override
    public TaskSummary planWork(Actor actor, TaskId id, LocalDate plannedDate, long expectedVersion) {
        access.requireMember(actor);
        Task task = loadVersion(id, expectedVersion);
        TaskSnapshot before = TaskSnapshot.of(task);
        task.planWork(plannedDate, clock.instant());
        tasks.save(task);
        return presenter.summarize(recorder.record(before, id, ChangeActor.of(actor), TaskAction.PLANNED));
    }

    @Override
    public TaskSummary changeDueDate(Actor actor, TaskId id, LocalDate dueDate, long expectedVersion) {
        access.requireMember(actor);
        Task task = loadVersion(id, expectedVersion);
        TaskSnapshot before = TaskSnapshot.of(task);
        task.changeDueDate(dueDate, clock.instant());
        tasks.save(task);
        return presenter.summarize(recorder.record(before, id, ChangeActor.of(actor),
                TaskAction.DUE_DATE_CHANGED));
    }

    @Override
    public TaskSummary complete(Actor actor, TaskId id, long expectedVersion) {
        access.requireMember(actor);
        Task task = loadVersion(id, expectedVersion);
        TaskSnapshot before = TaskSnapshot.of(task);
        task.complete(actor.memberId(), clock.instant());
        tasks.save(task);
        notifyCompletion(task, actor);
        return presenter.summarize(recorder.record(before, id, ChangeActor.of(actor), TaskAction.COMPLETED));
    }

    @Override
    public TaskSummary reopen(Actor actor, TaskId id, long expectedVersion) {
        access.requireMember(actor);
        Task task = loadVersion(id, expectedVersion);
        TaskSnapshot before = TaskSnapshot.of(task);
        task.reopen(clock.instant());
        tasks.save(task);
        return presenter.summarize(recorder.record(before, id, ChangeActor.of(actor), TaskAction.REOPENED));
    }

    @Override
    public TaskSummary moveToTrash(Actor actor, TaskId id, long expectedVersion) {
        access.requireMember(actor);
        Task task = load(id);
        if (task.isTrashed()) {
            return presenter.summarize(task);
        }
        requireVersion(task, expectedVersion);
        TaskSnapshot before = TaskSnapshot.of(task);
        task.moveToTrash(actor.memberId(), clock.instant(), recoveryWindow);
        tasks.save(task);
        return presenter.summarize(recorder.record(before, id, ChangeActor.of(actor), TaskAction.TRASHED));
    }

    @Override
    public TaskSummary restoreFromTrash(Actor actor, TaskId id, long expectedVersion) {
        access.requireMember(actor);
        Task task = load(id);
        if (!task.isTrashed()) {
            return presenter.summarize(task);
        }
        requireVersion(task, expectedVersion);
        TaskSnapshot before = TaskSnapshot.of(task);
        task.restoreFromTrash(task.owner().map(this::isActiveMember).orElse(true), clock.instant());
        tasks.save(task);
        recorder.record(before, id, ChangeActor.of(actor), TaskAction.RESTORED);
        // A restored cancellation review is checked against its contract at once: it gets its reminder back, or is
        // superseded when the contract was resolved or trashed in the meantime.
        task.cancellationReview().ifPresent(review -> cancellations.reconcile(actor, review.contractId()));
        return presenter.summarize(reload(id));
    }

    @Override
    public void purge(Actor actor, TaskId id, long expectedVersion) {
        access.requireMember(actor);
        Task task = tasks.findById(id).orElse(null);
        if (task == null) {
            if (tasks.wasPurged(id)) {
                return;
            }
            throw new NotFoundException("task", id.toString());
        }
        if (!task.isTrashed()) {
            throw PurgeConflictException.notTrashed("task", id.toString());
        }
        requireVersion(task, expectedVersion);
        tasks.purge(task, actor.memberId(), clock.instant());
        events.taskPurged(id);
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
                record.completedAt(), record.completedBy(), record.trash(), 0);
        tasks.save(task);
        // The exported task's earlier history is not part of the export; the history starts with this restore.
        recorder.record(TaskSnapshot.NONE, task.id(), ChangeActor.of(actor), TaskAction.IMPORTED);
        return ImportOutcome.IMPORTED;
    }

    @Override
    public TaskSummary undo(Actor actor, TaskId id, UUID changeId, long expectedVersion) {
        access.requireMember(actor);
        TaskChange change = history.find(changeId).filter(c -> c.taskId().equals(id))
                .orElseThrow(() -> new NotFoundException("taskChange", changeId.toString()));
        if (!change.undoable()) {
            throw UndoConflictException.notSupported(changeId.toString());
        }
        if (history.undoneBy(changeId).isPresent()) {
            throw UndoConflictException.alreadyUndone(changeId.toString());
        }
        Task task = loadVersion(id, expectedVersion);
        task.requireNotTrashed();
        TaskSnapshot before = TaskSnapshot.of(task);
        List<String> overwritten = change.overwrittenIn(before);
        if (!overwritten.isEmpty()) {
            throw UndoConflictException.changedSince(changeId.toString(), String.join(",", overwritten));
        }
        revert(task, change, actor);
        tasks.save(task);
        if (!Objects.equals(before.owner(), task.owner().orElse(null))) {
            notifyAssignment(task, actor);
        }
        if (task.status() == TaskStatus.DONE && before.status() != TaskStatus.DONE) {
            notifyCompletion(task, actor);
        }
        return presenter.summarize(recorder.recordUndo(change, before, ChangeActor.of(actor)));
    }

    /** Sets each changed field back to its earlier value through the task's own operations and their rules. */
    private void revert(Task task, TaskChange undone, Actor actor) {
        String title = task.title();
        String note = task.note();
        boolean text = false;
        for (FieldChange change : undone.changes()) {
            String value = change.before();
            switch (TaskField.ofCode(change.field())) {
                case TITLE -> {
                    title = value;
                    text = true;
                }
                case NOTE -> {
                    note = value;
                    text = true;
                }
                case OWNER -> {
                    MemberId owner = value == null ? null : MemberId.of(value);
                    if (owner != null && !isActiveMember(owner)) {
                        throw new UndoConflictException("ownerInactive", undone.id().toString(),
                                "the earlier owner is no longer an active member", Map.of());
                    }
                    task.assign(owner, clock.instant());
                }
                case DUE_DATE -> task.changeDueDate(value == null ? null : LocalDate.parse(value), clock.instant());
                case PLANNED_DATE -> task.planWork(value == null ? null : LocalDate.parse(value), clock.instant());
                case STATUS -> {
                    if (TaskStatus.OPEN.name().equals(value)) {
                        task.reopen(clock.instant());
                    } else if (TaskStatus.DONE.name().equals(value)) {
                        task.complete(actor.memberId(), clock.instant());
                    } else {
                        throw new IllegalStateException("status " + value + " is never the target of an undo");
                    }
                }
            }
        }
        if (text) {
            task.edit(title, note, clock.instant());
        }
    }

    private void notifyCompletion(Task task, Actor actor) {
        task.owner().filter(owner -> !owner.equals(actor.memberId()))
                .ifPresent(owner -> events.taskCompleted(task.id(), task.title(), owner, actor.memberId()));
    }

    private void notifyAssignment(Task task, Actor actor) {
        task.owner().filter(owner -> !owner.equals(actor.memberId()))
                .ifPresent(owner -> events.taskAssigned(task.id(), task.title(), owner, actor.memberId()));
    }

    private void requireOwner(MemberId ownerId) {
        if (ownerId != null && !isActiveMember(ownerId)) {
            throw new InvalidInputException("validation.failed", "Task owner must be an active household member");
        }
    }

    private boolean isActiveMember(MemberId memberId) {
        try {
            access.requireMember(new Actor(memberId));
            return true;
        } catch (dev.aegis.shared.NotAuthorizedException notMember) {
            return false;
        }
    }

    /** Re-reads the aggregate so the returned version is the persisted one, not the pre-save value. */
    private Task reload(TaskId id) {
        return tasks.findById(id).orElseThrow(() -> new NotFoundException("task", id.toString()));
    }

    private Task loadVersion(TaskId id, long expectedVersion) {
        Task task = load(id);
        requireVersion(task, expectedVersion);
        return task;
    }

    private Task load(TaskId id) {
        return tasks.findById(id).orElseThrow(() -> new NotFoundException("task", id.toString()));
    }

    private static void requireVersion(Task task, long expectedVersion) {
        if (task.version() != expectedVersion) {
            throw new StaleVersionException("task", task.id().toString(), expectedVersion, task.version());
        }
    }
}
