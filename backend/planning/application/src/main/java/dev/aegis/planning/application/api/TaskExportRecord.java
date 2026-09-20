package dev.aegis.planning.application.api;

import dev.aegis.shared.DocumentId;
import dev.aegis.shared.ContractId;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.TaskId;
import java.time.Instant;
import java.time.LocalDate;

/** Versioned export view of a task including its stable source link. */
public record TaskExportRecord(TaskId id, String title, String note, MemberId ownerId, String status,
        LocalDate dueDate, LocalDate plannedDate, DocumentId sourceDocumentId, MemberId createdBy, Instant createdAt,
        Instant updatedAt, Instant completedAt, MemberId completedBy, ContractId contractId, String contractName,
        LocalDate contractEnd, LocalDate cancellationDeadline, LocalDate reminderDate, Long contractVersion) {
}
