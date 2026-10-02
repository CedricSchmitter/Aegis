package dev.aegis.documents.application.ports;

import dev.aegis.documents.domain.Checksum;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Durable storage of the new pages scanned for a page editor request (ADR-0024), in a subdirectory of ingress that the
 * reconciliation of originals never touches. Each attempt writes its own file, so a repeated request never
 * overwrites the scan of an operation that is already stored.
 */
public interface ScanStore {

    /** {@code pdf} is false when the content does not start like a PDF. */
    record StoredScan(String path, long sizeBytes, Checksum checksum, boolean pdf) {
    }

    /** Writes the content durably (fsync) under a new name for the operation. */
    StoredScan store(UUID pageEdit, InputStream content) throws IOException;

    boolean exists(String path);

    Path resolve(String path);

    void delete(String path) throws IOException;
}
