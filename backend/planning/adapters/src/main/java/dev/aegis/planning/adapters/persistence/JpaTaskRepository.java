package dev.aegis.planning.adapters.persistence;

import dev.aegis.planning.domain.Task;
import dev.aegis.planning.domain.TaskRepository;
import dev.aegis.planning.domain.TaskSource;
import dev.aegis.planning.domain.TaskStatus;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.StaleVersionException;
import dev.aegis.shared.TaskId;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/** Domain repository over JPA; optimistic locking failures become {@link StaleVersionException}. */
@Component
public class JpaTaskRepository implements TaskRepository {
    private final TaskJpaRepository jpa;

    JpaTaskRepository(TaskJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<Task> findById(TaskId id) {
        return jpa.findById(id.value()).map(JpaTaskRepository::toDomain);
    }

    @Override
    public List<Task> findAll() {
        return jpa.findAllOrdered().stream().map(JpaTaskRepository::toDomain).toList();
    }

    @Override
    public List<Task> findOpen() {
        return jpa.findOpenOrdered().stream().map(JpaTaskRepository::toDomain).toList();
    }

    @Override
    public List<Task> findBySourceDocument(DocumentId documentId) {
        return jpa.findBySourceDocumentIdOrderByCreatedAtAsc(documentId.value()).stream()
                .map(JpaTaskRepository::toDomain).toList();
    }

    @Override
    public void save(Task task) {
        TaskEntity entity = jpa.findById(task.id().value()).orElseGet(() -> new TaskEntity(task.id().value()));
        if (entity.getVersion() != null && entity.getVersion() != task.version()) {
            throw new StaleVersionException("task", task.id().toString(), task.version(), entity.getVersion());
        }
        entity.setTitle(task.title());
        entity.setNote(task.note());
        entity.setOwnerId(task.owner().map(MemberId::value).orElse(null));
        entity.setStatus(task.status().name());
        entity.setDueDate(task.dueDate().orElse(null));
        entity.setPlannedDate(task.plannedDate().orElse(null));
        entity.setSourceDocumentId(task.source().map(s -> s.documentId().value()).orElse(null));
        entity.setCreatedBy(task.createdBy().value());
        entity.setCreatedAt(task.createdAt());
        entity.setUpdatedAt(task.updatedAt());
        entity.setCompletedAt(task.completedAt().orElse(null));
        entity.setCompletedBy(task.completedBy().map(MemberId::value).orElse(null));
        try {
            jpa.saveAndFlush(entity);
        } catch (OptimisticLockingFailureException e) {
            throw new StaleVersionException("task", task.id().toString(), task.version(), -1);
        }
    }

    private static Task toDomain(TaskEntity e) {
        return Task.rehydrate(new TaskId(e.getId()), e.getTitle(), e.getNote(),
                e.getOwnerId() == null ? null : new MemberId(e.getOwnerId()), TaskStatus.valueOf(e.getStatus()),
                e.getDueDate(), e.getPlannedDate(),
                e.getSourceDocumentId() == null ? null : new TaskSource(new DocumentId(e.getSourceDocumentId())),
                new MemberId(e.getCreatedBy()), e.getCreatedAt(), e.getUpdatedAt(), e.getCompletedAt(),
                e.getCompletedBy() == null ? null : new MemberId(e.getCompletedBy()),
                e.getVersion() == null ? 0 : e.getVersion());
    }
}
