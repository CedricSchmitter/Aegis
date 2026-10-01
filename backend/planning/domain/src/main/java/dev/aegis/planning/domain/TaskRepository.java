package dev.aegis.planning.domain;

import dev.aegis.shared.DocumentId;
import dev.aegis.shared.ContractId;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.TaskId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Aggregate persistence for tasks with optimistic locking on the version. */
public interface TaskRepository {
    Optional<Task> findById(TaskId id);

    /** All tasks including those in the trash: open first by due date, then done by completion time descending. */
    List<Task> findAll();

    /** Open tasks that are not in the trash, by due date. */
    List<Task> findOpen();

    /** Tasks in the trash, most recently trashed first. */
    List<Task> findTrashed();

    List<Task> findBySourceDocument(DocumentId documentId);

    /** Automatic cancellation-review occurrences for one contract, including completed and superseded work. */
    List<Task> findCancellationReviews(ContractId contractId);

    /**
     * Persists the aggregate.
     *
     * @throws dev.aegis.shared.StaleVersionException when the stored version differs from the aggregate version
     */
    void save(Task task);

    /**
     * Deletes the task (ADR-0021) and records who deleted it and when, without its title or note.
     *
     * @throws dev.aegis.shared.StaleVersionException when the stored version differs from the aggregate version
     */
    void purge(Task task, MemberId purgedBy, Instant purgedAt);

    /** Whether a task with this id was deleted permanently. */
    boolean wasPurged(TaskId id);
}
