package dev.aegis.planning.application.api;

import dev.aegis.shared.Actor;
import dev.aegis.shared.FieldChange;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.TaskId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The recorded history of one task (ADR-0029). Both members read every task's history. */
public interface TaskHistoryQueries {

    /**
     * One recorded change. {@code actorKind} is {@code MEMBER}, {@code AUTOMATION} or {@code AI_RUN};
     * {@code undoable} says whether an undo is offered now, {@code undoneBy} names the change that already reversed it.
     */
    record TaskChangeView(UUID id, long taskVersion, Instant occurredAt, String actorKind, MemberId actorMember,
            String actorReference, String action, List<FieldChange> changes, UUID undoOf, UUID undoneBy,
            boolean undoable) {
    }

    /**
     * A page of history, newest first. {@code recordingSince} is when recording began: a task created earlier has
     * changes that were never recorded. {@code nextBeforeVersion} continues the page; null on the last page.
     */
    record TaskHistoryPage(TaskId taskId, Instant recordingSince, List<TaskChangeView> changes,
            Long nextBeforeVersion) {
    }

    TaskHistoryPage history(Actor actor, TaskId taskId, Long beforeVersion, int limit);
}
