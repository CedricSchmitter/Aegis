package dev.aegis.operations.application;

import dev.aegis.operations.application.api.BackupHealth;
import dev.aegis.operations.application.api.DiskSpace;
import dev.aegis.operations.application.api.HealthCheck;
import dev.aegis.operations.application.api.JobHealth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns the backup, the disks and the recurring jobs into lines of the status overview. The backup
 * is always listed, because a household without a working backup has a problem; recurring jobs are listed only while
 * one of them does not run as planned (ADR-0013). The watches and their notices (ADR-0014, ADR-0015) do not depend on
 * this mapping.
 */
final class StatusOverview {
    static final String BACKUP = "backup";
    static final String STORAGE = "storage";
    static final String JOBS = "jobs";

    static final int BACKUP_ORDER = 30;
    static final int STORAGE_ORDER = 35;

    /** Job states in which a job does not run as planned; a job waiting for its first run is fine. */
    private static final Set<String> JOB_ATTENTION = Set.of(JobHealth.FAILING, JobHealth.STALE,
            JobHealth.NOT_SCHEDULED, JobHealth.UNKNOWN);

    private StatusOverview() {
    }

    static HealthCheck backup(BackupHealth backup) {
        return switch (backup.state()) {
            case BackupHealth.OK -> HealthCheck.ok(BACKUP, "backup.ok", backup.lastSuccessAt());
            case BackupHealth.RUNNING -> HealthCheck.ok(BACKUP, "backup.running", backup.lastSuccessAt());
            case BackupHealth.FAILED -> problem(BACKUP, "backup.failed", failureCode(backup), backup);
            case BackupHealth.STALE -> problem(BACKUP, "backup.stale", Map.of(), backup);
            case BackupHealth.INTERRUPTED -> problem(BACKUP, "backup.interrupted", Map.of(), backup);
            case BackupHealth.NOT_CONFIGURED -> problem(BACKUP, "backup.notConfigured", Map.of(), backup);
            default -> problem(BACKUP, "backup.unknown", failureCode(backup), backup);
        };
    }

    /** A low disk comes first; a disk that could not be measured is a problem too. */
    static HealthCheck storage(List<DiskSpace> disks) {
        String low = names(disks, DiskSpace.LOW);
        if (!low.isEmpty()) {
            return HealthCheck.problem(STORAGE, "storage.low", Map.of("disks", low), null);
        }
        String unknown = names(disks, DiskSpace.UNKNOWN);
        if (!unknown.isEmpty()) {
            return HealthCheck.problem(STORAGE, "storage.unmeasured", Map.of("disks", unknown), null);
        }
        return HealthCheck.ok(STORAGE, "storage.ok", null);
    }

    /** A line only while a job does not run as planned; it names those jobs. */
    static Optional<HealthCheck> jobs(List<JobHealth> jobs) {
        String names = jobs.stream().filter(job -> JOB_ATTENTION.contains(job.state())).map(JobHealth::name)
                .collect(Collectors.joining(","));
        return names.isEmpty() ? Optional.empty()
                : Optional.of(HealthCheck.problem(JOBS, "jobs.notRunning", Map.of("jobs", names), null));
    }

    /** A probe that threw although it must not: its area is reported as a problem without the exception's text. */
    static HealthCheck probeFailed(String area) {
        return HealthCheck.problem(area, "probe.failed", Map.of(), null);
    }

    private static HealthCheck problem(String area, String reason, Map<String, String> params, BackupHealth backup) {
        return HealthCheck.problem(area, reason, params, backup.lastSuccessAt());
    }

    private static Map<String, String> failureCode(BackupHealth backup) {
        Map<String, String> params = new HashMap<>();
        if (backup.failureCode() != null) {
            params.put("failureCode", backup.failureCode());
        }
        return params;
    }

    private static String names(List<DiskSpace> disks, String state) {
        return disks.stream().filter(disk -> state.equals(disk.state())).map(DiskSpace::name)
                .collect(Collectors.joining(","));
    }
}
