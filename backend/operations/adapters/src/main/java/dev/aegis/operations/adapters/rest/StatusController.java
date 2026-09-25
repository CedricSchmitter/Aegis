package dev.aegis.operations.adapters.rest;

import dev.aegis.operations.application.api.BackupHealth;
import dev.aegis.operations.application.api.DiskSpace;
import dev.aegis.operations.application.api.IntegrationStatus;
import dev.aegis.operations.application.api.JobHealth;
import dev.aegis.operations.application.api.StorageHealth;
import dev.aegis.operations.application.api.SystemStatus;
import dev.aegis.operations.application.api.SystemStatusQuery;
import dev.aegis.shared.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operational status for members. */
@RestController
@RequestMapping("/api/status")
@Tag(name = "status")
public class StatusController {
    private final SystemStatusQuery status;

    public StatusController(SystemStatusQuery status) {
        this.status = status;
    }

    public record IntegrationStatusDto(String name, String state, @Schema(nullable = true) String detail,
            @Schema(nullable = true) Instant lastSuccessAt, @Schema(nullable = true) Instant checkedAt) {
        static IntegrationStatusDto from(IntegrationStatus s) {
            return new IntegrationStatusDto(s.name(), s.state(), s.detail(), s.lastSuccessAt(), s.checkedAt());
        }
    }

    @Schema(description = "Whether a recurring background job still runs; names the job only, never its data")
    public record JobHealthDto(String name,
            @Schema(allowableValues = {"OK", "FAILING", "STALE", "PENDING", "NOT_SCHEDULED", "UNKNOWN"}) String state,
            long intervalSeconds, @Schema(nullable = true) Instant lastSuccessAt,
            @Schema(nullable = true) Instant lastFailureAt, int consecutiveFailures,
            @Schema(nullable = true) Instant nextRunAt) {
        static JobHealthDto from(JobHealth j) {
            return new JobHealthDto(j.name(), j.state(), j.interval().toSeconds(), j.lastSuccessAt(),
                    j.lastFailureAt(), j.consecutiveFailures(), j.nextRunAt());
        }
    }

    @Schema(description = "The external backup as recorded by the host's backup script; codes only, never output")
    public record BackupHealthDto(
            @Schema(allowableValues = {"OK", "RUNNING", "FAILED", "STALE", "INTERRUPTED", "NOT_CONFIGURED",
                    "UNKNOWN"}) String state,
            @Schema(nullable = true, description = "Stable failure code, set only when the state is FAILED or UNKNOWN")
            String failureCode,
            @Schema(nullable = true) Instant lastSuccessAt, @Schema(nullable = true) Instant lastAttemptAt,
            @Schema(nullable = true) Instant runningSince) {
        static BackupHealthDto from(BackupHealth b) {
            return new BackupHealthDto(b.state(), b.failureCode(), b.lastSuccessAt(), b.lastAttemptAt(),
                    b.runningSince());
        }
    }

    @Schema(description = "Free space of a disk Aegis depends on; byte values are null when it could not be measured")
    public record DiskSpaceDto(@Schema(allowableValues = {"data", "backup"}) String name,
            @Schema(allowableValues = {"OK", "LOW", "UNKNOWN"}) String state,
            @Schema(nullable = true) Long totalBytes, @Schema(nullable = true) Long freeBytes,
            @Schema(nullable = true, description = "The disk is LOW below this many free bytes") Long lowBelowBytes,
            Instant measuredAt) {
        static DiskSpaceDto from(DiskSpace d) {
            return new DiskSpaceDto(d.name(), d.state(), d.totalBytes(), d.freeBytes(), d.lowBelowBytes(),
                    d.measuredAt());
        }
    }

    public record StorageHealthDto(BackupHealthDto backup, List<DiskSpaceDto> disks) {
        static StorageHealthDto from(StorageHealth s) {
            return new StorageHealthDto(BackupHealthDto.from(s.backup()),
                    s.disks().stream().map(DiskSpaceDto::from).toList());
        }
    }

    public record SystemStatusDto(List<IntegrationStatusDto> integrations, Map<String, Long> documentProcessing,
            List<JobHealthDto> jobs, StorageHealthDto storage, Instant generatedAt) {
        static SystemStatusDto from(SystemStatus s) {
            return new SystemStatusDto(s.integrations().stream().map(IntegrationStatusDto::from).toList(),
                    s.documentProcessing(), s.jobs().stream().map(JobHealthDto::from).toList(),
                    StorageHealthDto.from(s.storage()), s.generatedAt());
        }
    }

    @Operation(operationId = "systemStatus",
            summary = "Integration, processing, recurring job, backup and disk space status with last success times")
    @GetMapping
    public SystemStatusDto status(Actor actor) {
        return SystemStatusDto.from(status.status(actor));
    }
}
