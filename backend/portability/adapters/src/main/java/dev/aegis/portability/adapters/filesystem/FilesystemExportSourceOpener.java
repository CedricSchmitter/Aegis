package dev.aegis.portability.adapters.filesystem;

import dev.aegis.portability.application.ports.ExportSource;
import dev.aegis.portability.application.ports.ExportSourceOpener;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/** Opens an export generation directory for restore. */
@Component
public class FilesystemExportSourceOpener implements ExportSourceOpener {

    @Override
    public ExportSource open(String location) throws IOException {
        Path root = Path.of(location).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IOException("export directory does not exist: " + root);
        }
        return new ExportSource() {
            @Override
            public boolean exists(String relativePath) {
                return Files.isRegularFile(resolve(relativePath));
            }

            @Override
            public byte[] read(String relativePath) throws IOException {
                return Files.readAllBytes(resolve(relativePath));
            }

            @Override
            public String location() {
                return root.toString();
            }

            private Path resolve(String relativePath) {
                Path resolved = root.resolve(relativePath).normalize();
                if (!resolved.startsWith(root)) {
                    throw new IllegalArgumentException("export path escapes generation: " + relativePath);
                }
                return resolved;
            }
        };
    }
}
