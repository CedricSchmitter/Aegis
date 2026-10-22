package dev.aegis.portability.application.api;

import java.time.Instant;
import java.util.List;

/**
 * A published export generation. {@code missingOriginals} lists document ids whose original could not be included;
 * the generation is still complete for every other record and says so in its manifest. {@code trigger} is
 * {@code MANUAL} or {@code SCHEDULED} and {@code dataFingerprint} identifies the exported data; both are null for
 * generations written before the daily export existed.
 */
public record ExportSummary(String generation, Instant createdAt, int documentCount, int taskCount, int memberCount,
        int contractCount, List<String> missingOriginals, String location, String trigger, String dataFingerprint) {
    public static final String MANUAL = "MANUAL";
    public static final String SCHEDULED = "SCHEDULED";

    public ExportSummary withLocation(String newLocation) {
        return new ExportSummary(generation, createdAt, documentCount, taskCount, memberCount, contractCount,
                missingOriginals, newLocation, trigger, dataFingerprint);
    }
}
