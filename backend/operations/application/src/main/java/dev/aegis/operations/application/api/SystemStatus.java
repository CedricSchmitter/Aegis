package dev.aegis.operations.application.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Status page model: integrations plus document processing counts by state. */
public record SystemStatus(List<IntegrationStatus> integrations, Map<String, Long> documentProcessing,
        Instant generatedAt) {
}
