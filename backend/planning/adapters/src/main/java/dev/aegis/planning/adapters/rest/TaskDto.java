package dev.aegis.planning.adapters.rest;

import dev.aegis.planning.application.api.TaskSummary;
import dev.aegis.shared.Trash;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;

/**
 * REST representation of a task. Dates are ISO local dates; instants are ISO-8601 UTC. {@code trash} is null unless
 * the task is in the trash.
 */
public record TaskDto(String id, String title, String note, @Schema(nullable = true) String ownerId, String status,
        @Schema(nullable = true) LocalDate dueDate, @Schema(nullable = true) LocalDate plannedDate,
        @Schema(nullable = true) SourceDto source, @Schema(nullable = true) CancellationReviewDto cancellationReview,
        String createdBy, Instant createdAt, Instant updatedAt,
        @Schema(nullable = true) Instant completedAt, @Schema(nullable = true) String completedBy, long version,
        @Schema(nullable = true) TaskTrashDto trash) {

    /** {@code documentTrashed} says the linked document is in the trash, where it can be restored. */
    public record SourceDto(String documentId, @Schema(nullable = true) String documentTitle,
            boolean documentTrashed) {
    }

    /** Who moved the task to the trash, when, and until when it is guaranteed to stay restorable. */
    public record TaskTrashDto(String trashedBy, Instant trashedAt, Instant restorableUntil) {
        static TaskTrashDto from(Trash trash) {
            return trash == null ? null : new TaskTrashDto(trash.trashedBy().toString(), trash.trashedAt(),
                    trash.restorableUntil());
        }
    }

    public record CancellationReviewDto(String contractId, String contractName,
            @Schema(nullable = true) LocalDate contractEnd, LocalDate cancellationDeadline,
            LocalDate reminderDate, long contractVersion) {
    }

    static TaskDto from(TaskSummary s) {
        SourceDto source = s.source() == null ? null
                : new SourceDto(s.source().documentId().toString(), s.source().documentTitle(),
                        s.source().documentTrashed());
        CancellationReviewDto cancellation = s.cancellationReview() == null ? null : new CancellationReviewDto(
                s.cancellationReview().contractId().toString(), s.cancellationReview().contractName(),
                s.cancellationReview().contractEnd(), s.cancellationReview().cancellationDeadline(),
                s.cancellationReview().reminderDate(), s.cancellationReview().contractVersion());
        return new TaskDto(s.id().toString(), s.title(), s.note(), s.ownerId() == null ? null : s.ownerId().toString(),
                s.status(), s.dueDate(), s.plannedDate(), source, cancellation, s.createdBy().toString(), s.createdAt(),
                s.updatedAt(), s.completedAt(), s.completedBy() == null ? null : s.completedBy().toString(),
                s.version(), TaskTrashDto.from(s.trash()));
    }
}
