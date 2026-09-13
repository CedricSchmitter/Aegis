package dev.aegis.portability.application.ports;

import dev.aegis.portability.application.api.ExportSummary;
import java.io.IOException;
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

    ExportStaging beginGeneration(String generation) throws IOException;

    /** Published generations, newest first, read from their manifests. */
    List<ExportSummary> listGenerations() throws IOException;

    Optional<ExportSummary> latest() throws IOException;
}
