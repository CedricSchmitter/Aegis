package dev.aegis.planning.adapters.rest;

import dev.aegis.planning.application.api.TaskSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;

/** REST representation of a task. Dates are ISO local dates; instants are ISO-8601 UTC. */
public record TaskDto(String id, String title, String note, @Schema(nullable = true) String ownerId, String status,
        @Schema(nullable = true) LocalDate dueDate, @Schema(nullable = true) LocalDate plannedDate,
        @Schema(nullable = true) SourceDto source, String createdBy, Instant createdAt, Instant updatedAt,
        @Schema(nullable = true) Instant completedAt, @Schema(nullable = true) String completedBy, long version) {

    public record SourceDto(String documentId, @Schema(nullable = true) String documentTitle) {
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
