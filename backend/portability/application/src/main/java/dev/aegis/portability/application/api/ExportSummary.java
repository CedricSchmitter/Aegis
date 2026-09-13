package dev.aegis.portability.application.api;

import java.time.Instant;
import java.util.List;

/**
 * A published export generation. {@code missingOriginals} lists document ids whose original could not be included;
 * the generation is still complete for every other record and says so in its manifest.
 */
public record ExportSummary(String generation, Instant createdAt, int documentCount, int taskCount, int memberCount,
        List<String> missingOriginals, String location) {
}
