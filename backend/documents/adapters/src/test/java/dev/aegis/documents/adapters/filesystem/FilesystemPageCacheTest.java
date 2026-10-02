package dev.aegis.documents.adapters.filesystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilesystemPageCacheTest {
    private static final Instant NOW = Instant.parse("2026-10-02T09:00:00Z");

    @TempDir
    Path dir;

    private FilesystemPageCache cache(long maxBytes) throws IOException {
        return new FilesystemPageCache(dir.toString(), maxBytes, Duration.ofDays(7), Clock.fixed(NOW,
                ZoneOffset.UTC));
    }

    private static void usedAt(Path file, Instant when) throws IOException {
        Files.setLastModifiedTime(file, FileTime.from(when));
    }

    @Test
    void sourcesAndPreviewsAreFoundAgain() throws IOException {
        FilesystemPageCache cache = cache(1_000_000);
        Path source = cache.storeSource("original-abc", new byte[] {1, 2, 3});
        cache.storePreview("original-abc", 2, 480, new byte[] {9});

        assertThat(cache.findSource("original-abc")).contains(source);
        assertThat(cache.findPreview("original-abc", 2, 480)).hasValueSatisfying(bytes ->
                assertThat(bytes).containsExactly(9));
        assertThat(cache.findPreview("original-abc", 3, 480)).isEmpty();
        assertThat(cache.findSource("original-def")).isEmpty();
        assertThatThrownBy(() -> cache.findSource("../escape")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void expiredAndLeastRecentlyUsedEntriesGoButRecentlyUsedOnesStay() throws IOException {
        FilesystemPageCache cache = cache(25);
        Path expired = cache.storeSource("expired", new byte[10]);
        usedAt(expired, NOW.minus(Duration.ofDays(8)));
        Path older = cache.storeSource("older", new byte[10]);
        usedAt(older, NOW.minus(Duration.ofHours(2)));
        Path newer = cache.storeSource("newer", new byte[10]);
        usedAt(newer, NOW.minus(Duration.ofHours(1)));

        Path recent = cache.storeSource("recent", new byte[10]);

        assertThat(expired).doesNotExist();
        assertThat(older).doesNotExist();
        assertThat(newer).exists();
        assertThat(recent).exists();
    }

    @Test
    void workFilesOfAnInterruptedRunAreRemovedAtStartup() throws IOException {
        Path work = cache(1_000).newWorkFile();
        assertThat(work).exists();

        cache(1_000);

        assertThat(work).doesNotExist();
    }
}
