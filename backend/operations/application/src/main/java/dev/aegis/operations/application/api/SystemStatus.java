package dev.aegis.operations.application.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Status page model: integrations, document processing counts by state, recurring background jobs, and the external
 * backup with the free space of the disks.
 */
public record SystemStatus(List<IntegrationStatus> integrations, Map<String, Long> documentProcessing,
        List<JobHealth> jobs, StorageHealth storage, Instant generatedAt) {
}
