package dev.aegis.planning.application.api;

import dev.aegis.shared.DocumentId;
import dev.aegis.shared.ContractId;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.TaskId;
import java.time.Instant;
import java.time.LocalDate;

/** Read model of a task. {@code source} is null for manually created tasks. */
public record TaskSummary(TaskId id, String title, String note, MemberId ownerId, String status, LocalDate dueDate,
        LocalDate plannedDate, SourceView source, CancellationReviewView cancellationReview,
        MemberId createdBy, Instant createdAt, Instant updatedAt,
        Instant completedAt, MemberId completedBy, long version) {

    /** {@code documentTitle} is null when the linked document no longer exists. */
    public record SourceView(DocumentId documentId, String documentTitle) {
    }

    public record CancellationReviewView(ContractId contractId, String contractName, LocalDate contractEnd,
            LocalDate cancellationDeadline, LocalDate reminderDate, long contractVersion) {
    }
}
