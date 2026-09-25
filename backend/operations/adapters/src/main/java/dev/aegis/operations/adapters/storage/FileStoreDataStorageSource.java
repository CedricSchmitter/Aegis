package dev.aegis.operations.adapters.storage;

import dev.aegis.operations.application.ports.DataStorageSource;
import dev.aegis.operations.application.ports.DiskUsage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Measures the file system of the household data through the JDK, without shell calls. On the VM the ingress
 * directory is a bind mount of {@code /srv/aegis/data/ingress}, so its file store is the disk that also holds the
 * archive, the databases and the exports.
 */
@Component
public class FileStoreDataStorageSource implements DataStorageSource {
    private final Path dataPath;
    private final Clock clock;

    public FileStoreDataStorageSource(@Value("${aegis.storage.data-path}") String dataPath, Clock clock) {
        this.dataPath = Path.of(dataPath);
        this.clock = clock;
    }

    @Override
    public DiskUsage measure() {
        try {
            FileStore store = Files.getFileStore(dataPath);
            return new DiskUsage(store.getTotalSpace(), store.getUsableSpace(), clock.instant());
        } catch (IOException e) {
            throw new UncheckedIOException("data storage cannot be measured", e);
        }
    }
}
