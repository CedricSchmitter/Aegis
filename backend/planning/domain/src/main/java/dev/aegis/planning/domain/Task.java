package dev.aegis.planning.domain;

import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.TaskId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * A household task with one responsible member or explicitly nobody. The actual due date and the planned work date
 * are independent: rescheduling work never moves a deadline.
 */
public final class Task {
    public static final int MAX_TITLE_LENGTH = 200;
    public static final int MAX_NOTE_LENGTH = 2000;

    private final TaskId id;
    private String title;
    private String note;
    private MemberId owner;
    private TaskStatus status;
    private LocalDate dueDate;
    private LocalDate plannedDate;
    private TaskSource source;
    private CancellationReview cancellationReview;
    private final MemberId createdBy;
    private final Instant createdAt;
    private Instant updatedAt;
    private Instant completedAt;
    private MemberId completedBy;
    private long version;

    private Task(TaskId id, String title, String note, MemberId owner, LocalDate dueDate, LocalDate plannedDate,
            TaskSource source, MemberId createdBy, Instant createdAt) {
        this.id = Objects.requireNonNull(id);
        this.title = requireTitle(title);
        this.note = normalizeNote(note);
        this.owner = owner;
        this.status = TaskStatus.OPEN;
        this.dueDate = dueDate;
        this.plannedDate = plannedDate;
        this.source = source;
        this.createdBy = Objects.requireNonNull(createdBy);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = createdAt;
    }

    public static Task create(String title, String note, MemberId owner, LocalDate dueDate, LocalDate plannedDate,
            TaskSource source, MemberId createdBy, Instant now) {
        return new Task(TaskId.random(), title, note, owner, dueDate, plannedDate, source, createdBy, now);
    }

    public static Task createCancellationReview(TaskId id, String title, LocalDate dueDate, TaskSource source,
            CancellationReview review, MemberId createdBy, Instant now) {
        Task task = new Task(id, title, "", null, dueDate, null, source, createdBy, now);
        task.cancellationReview = Objects.requireNonNull(review);
        return task;
    }

    /** Rehydration from persistence or import; no business validation beyond construction. */
    public static Task rehydrate(TaskId id, String title, String note, MemberId owner, TaskStatus status,
            LocalDate dueDate, LocalDate plannedDate, TaskSource source, MemberId createdBy, Instant createdAt,
            Instant updatedAt, Instant completedAt, MemberId completedBy, long version) {
        return rehydrate(id, title, note, owner, status, dueDate, plannedDate, source, null, createdBy, createdAt,
                updatedAt, completedAt, completedBy, version);
    }

    public static Task rehydrate(TaskId id, String title, String note, MemberId owner, TaskStatus status,
            LocalDate dueDate, LocalDate plannedDate, TaskSource source, CancellationReview cancellationReview,
            MemberId createdBy, Instant createdAt, Instant updatedAt, Instant completedAt, MemberId completedBy,
            long version) {
        Task task = new Task(id, title, note, owner, dueDate, plannedDate, source, createdBy, createdAt);
        task.status = Objects.requireNonNull(status);
        task.cancellationReview = cancellationReview;
        task.updatedAt = updatedAt == null ? createdAt : updatedAt;
        task.completedAt = completedAt;
        task.completedBy = completedBy;
        task.version = version;
        return task;
    }

    public void edit(String newTitle, String newNote, Instant now) {
        this.title = requireTitle(newTitle);
        this.note = normalizeNote(newNote);
        touch(now);
    }

    /** Assigns one responsible member; null makes the task explicitly unassigned. */
    public void assign(MemberId newOwner, Instant now) {
        this.owner = newOwner;
        touch(now);
    }

    /** Moves the planned work date only. The due date is untouched by design. */
    public void planWork(LocalDate newPlannedDate, Instant now) {
        this.plannedDate = newPlannedDate;
        touch(now);
    }

    /** Explicit deadline change, a separate decision from planning work. */
    public void changeDueDate(LocalDate newDueDate, Instant now) {
        if (cancellationReview != null) {
            throw new InvalidInputException("task.contractDeadline.managed",
                    "A cancellation review due date follows the confirmed contract deadline");
        }
        this.dueDate = newDueDate;
        touch(now);
    }

    /** Refreshes only contract-owned fields; member edits, owner and planned work date remain untouched. */
    public void reconcileCancellationReview(CancellationReview review, TaskSource source, Instant now) {
        if (cancellationReview == null || !cancellationReview.contractId().equals(review.contractId())
                || !cancellationReview.cancellationDeadline().equals(review.cancellationDeadline())) {
            throw new InvalidInputException("task.contractDeadline.occurrence",
                    "Cancellation review occurrence does not match");
        }
        if (title.equals(cancellationReview.contractName())) {
            title = review.contractName();
        }
        this.cancellationReview = Objects.requireNonNull(review);
        this.dueDate = review.cancellationDeadline();
        this.source = source;
        touch(now);
    }

    /** Supersedes unresolved automatic work without rewriting a member's completion decision. */
    public void cancelAutomatic(Instant now) {
        if (cancellationReview != null && status == TaskStatus.OPEN) {
            status = TaskStatus.CANCELLED;
            touch(now);
        }
    }

    public void complete(MemberId by, Instant now) {
        if (status != TaskStatus.OPEN) {
            throw new InvalidInputException("task.alreadyDone", "Task is already completed");
        }
        this.status = TaskStatus.DONE;
        this.completedAt = Objects.requireNonNull(now);
        this.completedBy = Objects.requireNonNull(by);
        touch(now);
    }

    public void reopen(Instant now) {
        if (status != TaskStatus.DONE) {
            throw new InvalidInputException("task.notDone", "Task is not completed");
        }
        this.status = TaskStatus.OPEN;
        this.completedAt = null;
        this.completedBy = null;
        touch(now);
    }

    public boolean isOpen() {
        return status == TaskStatus.OPEN;
    }

    public boolean isOverdueOn(LocalDate today) {
        return isOpen() && dueDate != null && dueDate.isBefore(today);
    }

    public boolean isDueOn(LocalDate day) {
        return isOpen() && dueDate != null && dueDate.equals(day);
    }

    public boolean isPlannedOn(LocalDate day) {
        return isOpen() && plannedDate != null && plannedDate.equals(day);
    }

    public boolean isUnassigned() {
        return owner == null;
    }

    private void touch(Instant now) {
        this.updatedAt = Objects.requireNonNull(now);
    }

    public TaskId id() {
        return id;
    }

    public String title() {
        return title;
    }

    public String note() {
        return note;
    }

    public Optional<MemberId> owner() {
        return Optional.ofNullable(owner);
    }

    public TaskStatus status() {
        return status;
    }

    public Optional<LocalDate> dueDate() {
        return Optional.ofNullable(dueDate);
    }

    public Optional<LocalDate> plannedDate() {
        return Optional.ofNullable(plannedDate);
    }

    public Optional<TaskSource> source() {
        return Optional.ofNullable(source);
    }

    public Optional<CancellationReview> cancellationReview() {
        return Optional.ofNullable(cancellationReview);
    }

    public MemberId createdBy() {
        return createdBy;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Optional<Instant> completedAt() {
        return Optional.ofNullable(completedAt);
    }

    public Optional<MemberId> completedBy() {
        return Optional.ofNullable(completedBy);
    }

    public long version() {
        return version;
    }

    private static String requireTitle(String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidInputException("task.title.missing", "Title is required");
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_TITLE_LENGTH) {
            throw new InvalidInputException("task.title.tooLong", "Title exceeds " + MAX_TITLE_LENGTH);
        }
        return trimmed;
    }

    private static String normalizeNote(String value) {
        if (value == null) {
            return "";
        }
        if (value.length() > MAX_NOTE_LENGTH) {
            throw new InvalidInputException("task.note.tooLong", "Note exceeds " + MAX_NOTE_LENGTH);
        }
        return value.trim();
    }
}
