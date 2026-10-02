package dev.aegis.documents.adapters.filesystem;

import dev.aegis.documents.application.ports.PageCache;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The page editor's cache of fetched sources and rendered previews (ADR-0024), in a directory outside the backed-up
 * data. Using an entry refreshes its modification time. After every write, entries older than the age bound are
 * removed, then the least recently used ones until the cache fits its size bound; entries used in the last few minutes
 * are kept, so a source an operation is reading does not disappear underneath it. Work files of an interrupted run are
 * removed at startup.
 */
@Component
public class FilesystemPageCache implements PageCache {
    private static final Logger log = LoggerFactory.getLogger(FilesystemPageCache.class);
    private static final Pattern KEY = Pattern.compile("[a-z0-9-]{1,100}");
    private static final Duration IN_USE = Duration.ofMinutes(10);

    private final Path sources;
    private final Path previews;
    private final Path work;
    private final long maxBytes;
    private final Duration maxAge;
    private final Clock clock;

    public FilesystemPageCache(@Value("${aegis.documents.page-edit.cache-dir}") String directory,
            @Value("${aegis.documents.page-edit.cache-max-bytes:1073741824}") long maxBytes,
            @Value("${aegis.documents.page-edit.cache-max-age:7d}") Duration maxAge, Clock clock)
            throws IOException {
        Path root = Path.of(directory).toAbsolutePath().normalize();
        this.sources = Files.createDirectories(root.resolve("sources"));
        this.previews = Files.createDirectories(root.resolve("previews"));
        this.work = Files.createDirectories(root.resolve("work"));
        this.maxBytes = maxBytes;
        this.maxAge = maxAge;
        this.clock = clock;
        for (Path leftover : list(work)) {
            Files.deleteIfExists(leftover);
        }
    }

    @Override
    public Optional<Path> findSource(String key) {
        return touched(sources.resolve(requireKey(key) + ".pdf"));
    }

    @Override
    public Path storeSource(String key, byte[] content) throws IOException {
        Path target = sources.resolve(requireKey(key) + ".pdf");
        write(target, content);
        evict();
        return target;
    }

    @Override
    public Optional<byte[]> findPreview(String key, int pageIndex, int longSidePixels) {
        return touched(previews.resolve(previewName(key, pageIndex, longSidePixels))).flatMap(file -> {
            try {
                return Optional.of(Files.readAllBytes(file));
            } catch (IOException evicted) {
                return Optional.empty();
            }
        });
    }

    @Override
    public void storePreview(String key, int pageIndex, int longSidePixels, byte[] jpeg) throws IOException {
        write(previews.resolve(previewName(key, pageIndex, longSidePixels)), jpeg);
        evict();
    }

    @Override
    public Path newWorkFile() throws IOException {
        return Files.createFile(work.resolve(UUID.randomUUID() + ".pdf"));
    }

    private Optional<Path> touched(Path file) {
        try {
            Files.setLastModifiedTime(file, FileTime.from(clock.instant()));
            return Optional.of(file);
        } catch (NoSuchFileException missing) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void write(Path target, byte[] content) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".part");
        try {
            Files.write(temp, content);
            Files.setLastModifiedTime(temp, FileTime.from(clock.instant()));
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Removes expired entries, then the least recently used ones beyond the size bound. */
    synchronized void evict() throws IOException {
        record Entry(Path file, Instant used, long size) {
        }
        List<Entry> entries = new ArrayList<>();
        for (Path directory : List.of(sources, previews)) {
            for (Path file : list(directory)) {
                try {
                    BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
                    entries.add(new Entry(file, attributes.lastModifiedTime().toInstant(), attributes.size()));
                } catch (NoSuchFileException gone) {
                    // Removed concurrently.
                }
            }
        }
        Instant now = clock.instant();
        entries.sort(Comparator.comparing(Entry::used));
        long total = entries.stream().mapToLong(Entry::size).sum();
        for (Entry entry : entries) {
            boolean expired = entry.used().isBefore(now.minus(maxAge));
            if (!expired && (total <= maxBytes || entry.used().isAfter(now.minus(IN_USE)))) {
                continue;
            }
            Files.deleteIfExists(entry.file());
            total -= entry.size();
        }
        if (total > maxBytes) {
            log.warn("Page cache holds {} bytes in recently used entries, above its bound of {}", total, maxBytes);
        }
    }

    private static List<Path> list(Path directory) throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path file : stream) {
                if (Files.isRegularFile(file) && !file.getFileName().toString().endsWith(".part")) {
                    files.add(file);
                }
            }
        }
        return files;
    }

    private static String previewName(String key, int pageIndex, int longSidePixels) {
        return requireKey(key) + "-p" + pageIndex + "-" + longSidePixels + ".jpg";
    }

    private static String requireKey(String key) {
        if (!KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("invalid page cache key");
        }
        return key;
    }
}
