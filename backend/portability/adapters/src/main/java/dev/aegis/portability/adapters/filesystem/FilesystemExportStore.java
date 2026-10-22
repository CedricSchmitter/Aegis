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
 * once complete (atomic directory move), and a {@code LATEST} pointer file updated last. {@code scheduled-run.json}
 * records when the daily export last finished and last failed.
 */
@Component
public class FilesystemExportStore implements ExportStore {
    static final String GENERATIONS = "generations";
    static final String STAGING = "staging";
    static final String LATEST = "LATEST";
    static final String SCHEDULED_RUN = "scheduled-run.json";

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
        Files.createDirectory(staging);
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
                replaceAtomically(LATEST, generation.getBytes(StandardCharsets.UTF_8));
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
        Optional<String> generation = latestGeneration();
        return generation.isEmpty() ? Optional.empty()
                : readSummary(root.resolve(GENERATIONS).resolve(generation.get()));
    }

    @Override
    public void deleteGeneration(String generation) throws IOException {
        Path generations = root.resolve(GENERATIONS);
        Path dir = generations.resolve(generation).normalize();
        if (generation.isBlank() || !generations.equals(dir.getParent())) {
            throw new IOException("not an export generation: " + generation);
        }
        if (generation.equals(latestGeneration().orElse(null))) {
            throw new IOException("the latest export generation is never deleted");
        }
        deleteRecursively(dir);
    }

    @Override
    public Optional<ScheduledRun> scheduledRun() throws IOException {
        Path file = root.resolve(SCHEDULED_RUN);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.of(serializer.fromJson(Files.readAllBytes(file), ScheduledRun.class));
    }

    @Override
    public void recordScheduledRun(ScheduledRun run) throws IOException {
        replaceAtomically(SCHEDULED_RUN, serializer.toJson(run));
    }

    private Optional<String> latestGeneration() throws IOException {
        Path pointer = root.resolve(LATEST);
        return Files.isRegularFile(pointer)
                ? Optional.of(Files.readString(pointer, StandardCharsets.UTF_8).trim())
                : Optional.empty();
    }

    private void replaceAtomically(String name, byte[] content) throws IOException {
        Path temp = Files.createTempFile(root, name + "-", ".tmp");
        Files.write(temp, content);
        Files.move(temp, root.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private Optional<ExportSummary> readSummary(Path generationDir) throws IOException {
        Path manifestFile = generationDir.resolve(ExportFormat.MANIFEST);
        if (!Files.isRegularFile(manifestFile)) {
            return Optional.empty();
        }
        Manifest manifest = serializer.fromJson(Files.readAllBytes(manifestFile), Manifest.class);
        return Optional.of(new ExportSummary(manifest.generation(), manifest.exportedAt(), manifest.documentCount(),
                manifest.taskCount(), manifest.memberCount(), manifest.contractCount(),
                manifest.missingOriginals(), generationDir.toString(), manifest.trigger(),
                manifest.dataFingerprint()));
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
