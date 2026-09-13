package dev.aegis.portability.adapters.filesystem;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.portability.adapters.json.JacksonExportSerializer;
import dev.aegis.portability.application.ExportFormat;
import dev.aegis.portability.application.ExportFormat.Manifest;
import dev.aegis.portability.application.ports.ExportSerializer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class FilesystemExportStoreTest {
    @TempDir
    Path dir;

    private final ExportSerializer serializer = new JacksonExportSerializer(JsonMapper.builder().build());

    @Test
    void publishedGenerationBecomesLatestAndDiscardedOneLeavesNoTrace() throws IOException {
        FilesystemExportStore store = new FilesystemExportStore(dir.toString(), serializer);

        var good = store.beginGeneration("20260913T100000Z");
        good.write("documents/originals/a.pdf", new byte[] {1, 2, 3});
        good.write(ExportFormat.MANIFEST, serializer.toJson(new Manifest(1, "20260913T100000Z",
                Instant.parse("2026-09-13T10:00:00Z"), "test", 1, 0, 2, List.of(), Map.of())));
        String location = good.publish();

        var bad = store.beginGeneration("20260913T110000Z");
        bad.write("data/tasks.json", new byte[] {4});
        bad.discard();

        assertThat(Files.isDirectory(Path.of(location))).isTrue();
        assertThat(Files.exists(dir.resolve(FilesystemExportStore.STAGING).resolve("20260913T110000Z"))).isFalse();
        assertThat(store.latest()).isPresent();
        assertThat(store.latest().orElseThrow().generation()).isEqualTo("20260913T100000Z");
        assertThat(store.listGenerations()).hasSize(1);
        assertThat(store.listGenerations().get(0).memberCount()).isEqualTo(2);
    }

    @Test
    void competingGenerationCannotDeleteAnActiveExport() throws IOException {
        FilesystemExportStore store = new FilesystemExportStore(dir.toString(), serializer);
        var active = store.beginGeneration("same-generation");
        active.write("original.pdf", new byte[] {1, 2, 3});
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.beginGeneration("same-generation"))
                .isInstanceOf(IOException.class);
        String published = active.publish();
        assertThat(Files.readAllBytes(Path.of(published).resolve("original.pdf"))).containsExactly(1, 2, 3);
    }

    @Test
    void manifestRoundTripsThroughJson() {
        Manifest manifest = new Manifest(1, "g", Instant.parse("2026-09-13T10:00:00Z"), "p", 1, 2, 3, List.of("x"),
                Map.of("a", "b"));

        Manifest parsed = serializer.fromJson(serializer.toJson(manifest), Manifest.class);

        assertThat(parsed).isEqualTo(manifest);
    }
}
