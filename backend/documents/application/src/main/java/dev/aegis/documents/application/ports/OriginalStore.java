package dev.aegis.documents.application.ports;

import dev.aegis.documents.domain.Checksum;
import dev.aegis.documents.domain.OriginalFormat;
import dev.aegis.shared.DocumentId;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

/** Durable staging (ingress) storage for accepted originals. Paths are relative to the ingress root. */
public interface OriginalStore {

    /**
     * A durably written file. {@code format} is the format recognized from its content ({@link OriginalFormat}), or
     * null when the content matches no accepted format; such a file is named without a format's extension.
     */
    record StoredOriginal(String stagingPath, long sizeBytes, Checksum checksum, OriginalFormat format) {
    }

    /** Writes the content durably (fsync), computes its checksum and recognizes its format from the first bytes. */
    StoredOriginal store(DocumentId id, InputStream content) throws IOException;

    boolean exists(String stagingPath);

    byte[] read(String stagingPath) throws IOException;

    /** Bounded staging read for derived text; filesystem adapters stop before allocating larger contents. */
    default byte[] readForText(String stagingPath, int maxBytes) throws IOException {
        byte[] bytes = read(stagingPath);
        if (bytes.length > maxBytes) { throw new IOException("staged text source exceeds its byte bound"); }
        return bytes;
    }

    Path resolve(String stagingPath);

    void delete(String stagingPath) throws IOException;

    /** Staging paths of every regular file currently in ingress (excluding quarantined orphans). */
    List<String> listStagingPaths() throws IOException;

    /** Moves a file without a database record aside so a human can inspect it; nothing is deleted. */
    void quarantineOrphan(String stagingPath) throws IOException;
}
