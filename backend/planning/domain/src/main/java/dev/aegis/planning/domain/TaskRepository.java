package dev.aegis.planning.domain;

import dev.aegis.shared.DocumentId;
import dev.aegis.shared.TaskId;
import java.util.List;
import java.util.Optional;

/** Aggregate persistence for tasks with optimistic locking on the version. */
public interface TaskRepository {
    Optional<Task> findById(TaskId id);

    /** All tasks: open first by due date, then done by completion time descending. */
    List<Task> findAll();

    List<Task> findOpen();

    List<Task> findBySourceDocument(DocumentId documentId);

    /**
     * Persists the aggregate.
     *
     * @throws dev.aegis.shared.StaleVersionException when the stored version differs from the aggregate version
     */
    void save(Task task);
}
