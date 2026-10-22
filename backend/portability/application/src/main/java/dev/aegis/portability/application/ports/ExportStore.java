package dev.aegis.portability.application.ports;

import dev.aegis.portability.application.api.ExportSummary;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Storage of export generations. A generation is written to a staging area and becomes visible only through
 * {@link ExportStaging#publish}; a failed generation never replaces a good one.
 */
public interface ExportStore {

    interface ExportStaging {
        void write(String relativePath, byte[] content) throws IOException;

        /** Atomically publishes the generation and marks it as latest. Returns the published location. */
        String publish() throws IOException;

        void discard() throws IOException;
    }

    /** When the daily export last finished and last failed; either may be null. */
    record ScheduledRun(Instant lastCheckedAt, Instant lastFailedAt) {
    }

    ExportStaging beginGeneration(String generation) throws IOException;

    /** Published generations, newest first, read from their manifests. */
    List<ExportSummary> listGenerations() throws IOException;

    Optional<ExportSummary> latest() throws IOException;

    /** Removes a published generation. The latest generation is never removed. */
    void deleteGeneration(String generation) throws IOException;

    Optional<ScheduledRun> scheduledRun() throws IOException;

    void recordScheduledRun(ScheduledRun run) throws IOException;
}
