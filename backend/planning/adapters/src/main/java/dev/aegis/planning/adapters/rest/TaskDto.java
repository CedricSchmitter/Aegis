package dev.aegis.planning.adapters.rest;

import dev.aegis.planning.application.api.TaskSummary;
import java.time.Instant;
import java.time.LocalDate;

/** REST representation of a task. Dates are ISO local dates; instants are ISO-8601 UTC. */
public record TaskDto(String id, String title, String note, String ownerId, String status, LocalDate dueDate,
        LocalDate plannedDate, SourceDto source, String createdBy, Instant createdAt, Instant updatedAt,
        Instant completedAt, String completedBy, long version) {

    public record SourceDto(String documentId, String documentTitle) {
    }

    static TaskDto from(TaskSummary s) {
        SourceDto source = s.source() == null ? null
                : new SourceDto(s.source().documentId().toString(), s.source().documentTitle());
        return new TaskDto(s.id().toString(), s.title(), s.note(), s.ownerId() == null ? null : s.ownerId().toString(),
                s.status(), s.dueDate(), s.plannedDate(), source, s.createdBy().toString(), s.createdAt(),
                s.updatedAt(), s.completedAt(), s.completedBy() == null ? null : s.completedBy().toString(),
                s.version());
    }
}
