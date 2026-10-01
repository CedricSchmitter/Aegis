package dev.aegis.planning.application.api;

import dev.aegis.shared.DocumentId;
import dev.aegis.shared.ContractId;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.TaskId;
import dev.aegis.shared.Trash;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Read model of a task. {@code source} is null for manually created tasks; {@code trash} is null unless the task is
 * in the trash.
 */
public record TaskSummary(TaskId id, String title, String note, MemberId ownerId, String status, LocalDate dueDate,
        LocalDate plannedDate, SourceView source, CancellationReviewView cancellationReview,
        MemberId createdBy, Instant createdAt, Instant updatedAt,
        Instant completedAt, MemberId completedBy, long version, Trash trash) {

    /**
     * {@code documentTrashed} says that the linked document is in the trash, where it can be restored;
     * {@code documentDeleted} that it no longer exists because a member deleted it permanently (ADR-0021), and then
     * {@code documentTitle} is null.
     */
    public record SourceView(DocumentId documentId, String documentTitle, boolean documentTrashed,
            boolean documentDeleted) {
    }

    /** {@code contractDeleted}: a completed review kept as history after its contract was deleted permanently. */
    public record CancellationReviewView(ContractId contractId, String contractName, LocalDate contractEnd,
            LocalDate cancellationDeadline, LocalDate reminderDate, long contractVersion, boolean contractDeleted) {
    }
}
