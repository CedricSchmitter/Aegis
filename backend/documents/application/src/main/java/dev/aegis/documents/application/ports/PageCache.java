package dev.aegis.documents.application.ports;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Derived data of the page editor (ADR-0024): source PDFs fetched from the archive and rendered page previews, kept in
 * a directory outside the backed-up data. Everything here can be generated again; entries disappear when the cache
 * exceeds its size or age bound. Keys are built from checksums, so an entry never goes stale.
 */
public interface PageCache {

    /** A cached source file, refreshed as recently used. */
    Optional<Path> findSource(String key);

    /** Stores a source file atomically and returns its path. */
    Path storeSource(String key, byte[] content) throws IOException;

    Optional<byte[]> findPreview(String key, int pageIndex, int longSidePixels);

    void storePreview(String key, int pageIndex, int longSidePixels, byte[] jpeg) throws IOException;

    /** A new empty file for writing an output; the caller deletes it. */
    Path newWorkFile() throws IOException;
}
