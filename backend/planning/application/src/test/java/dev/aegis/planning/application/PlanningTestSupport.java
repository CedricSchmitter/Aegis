package dev.aegis.planning.application;

import dev.aegis.documents.application.api.DocumentReference;
import dev.aegis.documents.application.api.DocumentReferenceQuery;
import dev.aegis.planning.application.ports.CalendarPort;
import dev.aegis.planning.application.ports.PlanningEvents;
import dev.aegis.planning.domain.Task;
import dev.aegis.planning.domain.TaskRepository;
import dev.aegis.shared.Actor;
import dev.aegis.shared.ContractId;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.HouseholdAccess;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.NotAuthorizedException;
import dev.aegis.shared.StaleVersionException;
import dev.aegis.shared.TaskId;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class PlanningTestSupport {
    private PlanningTestSupport() {
    }

    /** Emulates optimistic locking: saving an aggregate whose version lags the stored one fails. */
    static final class InMemoryTaskRepository implements TaskRepository {
        private final Map<TaskId, Task> store = new LinkedHashMap<>();
        private final Map<TaskId, Long> versions = new HashMap<>();

        @Override
        public Optional<Task> findById(TaskId id) {
            return Optional.ofNullable(store.get(id)).map(this::copy);
        }

        @Override
        public List<Task> findAll() {
            return store.values().stream().map(this::copy)
                    .sorted(Comparator.comparing((Task t) -> t.dueDate().orElse(LocalDate.MAX))).toList();
        }

        @Override
        public List<Task> findOpen() {
            return findAll().stream().filter(Task::isOpen).toList();
        }

        @Override
        public List<Task> findTrashed() {
            return findAll().stream().filter(Task::isTrashed)
                    .sorted(Comparator.comparing((Task t) -> t.trash().orElseThrow().trashedAt()).reversed())
                    .toList();
        }

        @Override
        public List<Task> findBySourceDocument(DocumentId documentId) {
            return findAll().stream()
                    .filter(t -> t.source().map(s -> s.documentId().equals(documentId)).orElse(false)).toList();
        }

        @Override
        public List<Task> findCancellationReviews(ContractId contractId) {
            return findAll().stream().filter(t -> t.cancellationReview()
                    .map(r -> r.contractId().equals(contractId)).orElse(false)).toList();
        }

        @Override
        public void save(Task task) {
            long stored = versions.getOrDefault(task.id(), -1L);
            if (stored >= 0 && stored != task.version()) {
                throw new StaleVersionException("task", task.id().toString(), task.version(), stored);
            }
            long next = stored + 1;
            versions.put(task.id(), next);
            store.put(task.id(), Task.rehydrate(task.id(), task.title(), task.note(), task.owner().orElse(null),
                    task.status(), task.dueDate().orElse(null), task.plannedDate().orElse(null),
                    task.source().orElse(null), task.cancellationReview().orElse(null), task.createdBy(),
                    task.createdAt(), task.updatedAt(), task.completedAt().orElse(null),
                    task.completedBy().orElse(null), task.trash().orElse(null), next));
        }

        private Task copy(Task task) {
            return Task.rehydrate(task.id(), task.title(), task.note(), task.owner().orElse(null), task.status(),
                    task.dueDate().orElse(null), task.plannedDate().orElse(null), task.source().orElse(null), task.cancellationReview().orElse(null),
                    task.createdBy(), task.createdAt(), task.updatedAt(), task.completedAt().orElse(null),
                    task.completedBy().orElse(null), task.trash().orElse(null), task.version());
        }
    }

    static final class KnownDocuments implements DocumentReferenceQuery {
        final Map<DocumentId, String> titles = new HashMap<>();
        final Set<DocumentId> trashed = new java.util.HashSet<>();

        @Override
        public Optional<DocumentReference> findReference(DocumentId id) {
            return Optional.ofNullable(titles.get(id)).map(t -> new DocumentReference(id, t, trashed.contains(id)));
        }
    }

    static final class RecordingEvents implements PlanningEvents {
        final List<String> events = new ArrayList<>();

        @Override
        public void taskAssigned(TaskId taskId, String title, MemberId assignee, MemberId assignedBy) {
            events.add("assigned:" + taskId + ":" + assignee);
        }

        @Override
        public void taskCompleted(TaskId taskId, String title, MemberId owner, MemberId completedBy) {
            events.add("completed:" + taskId + ":" + owner);
        }
    }

    static final class Members implements HouseholdAccess {
        private final Set<MemberId> members;

        Members(MemberId... ids) {
            this.members = Set.of(ids);
        }

        @Override
        public void requireMember(Actor actor) {
            if (actor == null || !members.contains(actor.memberId())) {
                throw new NotAuthorizedException("not a member");
            }
        }
    }

    static final class NoCalendar implements CalendarPort {
        @Override
        public Capabilities capabilities() {
            return Capabilities.notConnected();
        }

        @Override
        public List<CalendarEntry> entriesOn(LocalDate date) {
            return List.of();
        }
    }
}
