package dev.aegis.documents.application.ports;

import dev.aegis.documents.domain.Checksum;
import dev.aegis.shared.DocumentId;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

/** Durable staging (ingress) storage for accepted originals. Paths are relative to the ingress root. */
public interface OriginalStore {

    record StoredOriginal(String stagingPath, long sizeBytes, Checksum checksum, boolean pdfSignature) {
    }

    /** Writes the content durably (fsync), computes its checksum and detects the PDF signature. */
    StoredOriginal store(DocumentId id, InputStream content) throws IOException;

    boolean exists(String stagingPath);

    byte[] read(String stagingPath) throws IOException;

    Path resolve(String stagingPath);

    void delete(String stagingPath) throws IOException;

    /** Staging paths of every regular file currently in ingress (excluding quarantined orphans). */
    List<String> listStagingPaths() throws IOException;

    /** Moves a file without a database record aside so a human can inspect it; nothing is deleted. */
    void quarantineOrphan(String stagingPath) throws IOException;
}
