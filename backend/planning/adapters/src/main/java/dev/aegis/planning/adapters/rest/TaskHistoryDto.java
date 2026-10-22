package dev.aegis.planning.adapters.rest;

import dev.aegis.planning.application.api.TaskHistoryQueries.TaskChangeView;
import dev.aegis.planning.application.api.TaskHistoryQueries.TaskHistoryPage;
import dev.aegis.shared.FieldChange;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * A page of a task's history, newest first (ADR-0030). Field values are locale-independent (ISO dates, member ids,
 * status names) or the member's own text; {@code nextBeforeVersion} continues the page.
 */
public record TaskHistoryDto(String taskId, Instant recordingSince, List<TaskChangeDto> changes,
        @Schema(nullable = true) Long nextBeforeVersion) {

    /**
     * {@code actorKind}: MEMBER, AUTOMATION or AI_RUN. {@code actorReference} names the automation or AI run.
     * {@code undoable}: an undo is offered now; {@code undoneBy}: the change that already reversed this one.
     */
    public record TaskChangeDto(String id, long taskVersion, Instant occurredAt, String actorKind,
            @Schema(nullable = true) String actorMemberId, @Schema(nullable = true) String actorReference,
            String action, List<TaskFieldChangeDto> changes, @Schema(nullable = true) String undoOf,
            @Schema(nullable = true) String undoneBy, boolean undoable) {
    }

    /** One field: a stable code (title, note, owner, status, dueDate, plannedDate); null means no value. */
    public record TaskFieldChangeDto(String field, @Schema(nullable = true) String before,
            @Schema(nullable = true) String after) {
        static TaskFieldChangeDto from(FieldChange change) {
            return new TaskFieldChangeDto(change.field(), change.before(), change.after());
        }
    }

    static TaskHistoryDto from(TaskHistoryPage page) {
        return new TaskHistoryDto(page.taskId().toString(), page.recordingSince(),
                page.changes().stream().map(TaskHistoryDto::change).toList(), page.nextBeforeVersion());
    }

    private static TaskChangeDto change(TaskChangeView view) {
        return new TaskChangeDto(view.id().toString(), view.taskVersion(), view.occurredAt(), view.actorKind(),
                view.actorMember() == null ? null : view.actorMember().toString(), view.actorReference(),
                view.action(), view.changes().stream().map(TaskFieldChangeDto::from).toList(),
                view.undoOf() == null ? null : view.undoOf().toString(),
                view.undoneBy() == null ? null : view.undoneBy().toString(), view.undoable());
    }
}
