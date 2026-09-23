package dev.aegis.operations.application.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Status page model: integrations, document processing counts by state and recurring background jobs. */
public record SystemStatus(List<IntegrationStatus> integrations, Map<String, Long> documentProcessing,
        List<JobHealth> jobs, Instant generatedAt) {
}
