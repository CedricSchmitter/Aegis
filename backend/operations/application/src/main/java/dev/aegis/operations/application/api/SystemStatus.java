package dev.aegis.operations.application.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Status page model: the overview that answers "is everything OK?" (issue #83), and the technical details behind it:
 * document processing counts by state, recurring background jobs, and the external backup with the free space of the
 * disks.
 */
public record SystemStatus(List<HealthCheck> checks, Map<String, Long> documentProcessing, List<JobHealth> jobs,
        StorageHealth storage, Instant generatedAt) {
    public SystemStatus {
        checks = List.copyOf(checks);
    }
}
