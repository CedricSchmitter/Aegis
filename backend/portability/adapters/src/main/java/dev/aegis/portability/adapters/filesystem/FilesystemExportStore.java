package dev.aegis.portability.adapters.filesystem;

import dev.aegis.portability.application.ExportFormat;
import dev.aegis.portability.application.ExportFormat.Manifest;
import dev.aegis.portability.application.api.ExportSummary;
import dev.aegis.portability.application.ports.ExportSerializer;
import dev.aegis.portability.application.ports.ExportStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Export generations on the persistent data volume: {@code staging/<gen>} while writing, {@code generations/<gen>}
 * once complete (atomic directory move), and a {@code LATEST} pointer file updated last.
 */
@Component
public class FilesystemExportStore implements ExportStore {
    static final String GENERATIONS = "generations";
    static final String STAGING = "staging";
    static final String LATEST = "LATEST";

    private final Path root;
    private final ExportSerializer serializer;

    public FilesystemExportStore(@Value("${aegis.exports.dir}") String rootDir, ExportSerializer serializer)
            throws IOException {
        this.root = Path.of(rootDir).toAbsolutePath().normalize();
        this.serializer = serializer;
        Files.createDirectories(root.resolve(GENERATIONS));
        Files.createDirectories(root.resolve(STAGING));
    }

    @Override
    public ExportStaging beginGeneration(String generation) throws IOException {
        Path staging = root.resolve(STAGING).resolve(generation);
        Path target = root.resolve(GENERATIONS).resolve(generation);
        if (Files.exists(target)) {
            throw new IOException("export generation " + generation + " already exists");
        }
        deleteRecursively(staging);
        Files.createDirectories(staging);
        return new ExportStaging() {
            @Override
            public void write(String relativePath, byte[] content) throws IOException {
                Path file = staging.resolve(relativePath).normalize();
                if (!file.startsWith(staging)) {
                    throw new IOException("export path escapes generation: " + relativePath);
                }
                Files.createDirectories(file.getParent());
                Files.write(file, content);
            }

            @Override
            public String publish() throws IOException {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
                Path latestTemp = root.resolve(LATEST + ".tmp");
                Files.writeString(latestTemp, generation, StandardCharsets.UTF_8);
                Files.move(latestTemp, root.resolve(LATEST), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                return target.toString();
            }

            @Override
            public void discard() throws IOException {
                deleteRecursively(staging);
            }
        };
    }

    @Override
    public List<ExportSummary> listGenerations() throws IOException {
        List<ExportSummary> summaries = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(root.resolve(GENERATIONS))) {
            for (Path dir : dirs.filter(Files::isDirectory).sorted(Comparator.reverseOrder()).toList()) {
                readSummary(dir).ifPresent(summaries::add);
            }
        }
        return summaries;
    }

    @Override
    public Optional<ExportSummary> latest() throws IOException {
        Path pointer = root.resolve(LATEST);
        if (!Files.isRegularFile(pointer)) {
            return Optional.empty();
        }
        String generation = Files.readString(pointer, StandardCharsets.UTF_8).trim();
        return readSummary(root.resolve(GENERATIONS).resolve(generation));
    }

    private Optional<ExportSummary> readSummary(Path generationDir) throws IOException {
        Path manifestFile = generationDir.resolve(ExportFormat.MANIFEST);
        if (!Files.isRegularFile(manifestFile)) {
            return Optional.empty();
        }
        Manifest manifest = serializer.fromJson(Files.readAllBytes(manifestFile), Manifest.class);
        return Optional.of(new ExportSummary(manifest.generation(), manifest.exportedAt(), manifest.documentCount(),
                manifest.taskCount(), manifest.memberCount(), manifest.missingOriginals(),
                generationDir.toString()));
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
