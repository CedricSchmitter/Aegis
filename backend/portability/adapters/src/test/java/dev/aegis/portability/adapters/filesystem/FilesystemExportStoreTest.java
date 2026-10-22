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
                Instant.parse("2026-09-13T10:00:00Z"), "test", 1, 0, 2, 1, List.of(), Map.of(), null, null)));
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
        Manifest manifest = new Manifest(1, "g", Instant.parse("2026-09-13T10:00:00Z"), "p", 1, 2, 3, 4,
                List.of("x"), Map.of("a", "b"), "SCHEDULED", "f".repeat(64));

        Manifest parsed = serializer.fromJson(serializer.toJson(manifest), Manifest.class);

        assertThat(parsed).isEqualTo(manifest);
    }

    @Test
    void readsManifestsWrittenBeforeTheDailyExportWithoutTriggerOrFingerprint() {
        String legacy = """
                {"schemaVersion": 11, "generation": "g", "exportedAt": "2026-09-13T10:00:00Z", "producer": "p",
                 "documentCount": 0, "taskCount": 0, "memberCount": 1, "contractCount": 0,
                 "missingOriginals": [], "fileChecksumsSha256": {}}
                """;

        Manifest parsed = serializer.fromJson(legacy.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Manifest.class);

        assertThat(parsed.trigger()).isNull();
        assertThat(parsed.dataFingerprint()).isNull();
    }

    @Test
    void summaryCarriesTriggerAndFingerprintAndOlderGenerationsCanBeRemovedButNeverTheLatest() throws IOException {
        FilesystemExportStore store = new FilesystemExportStore(dir.toString(), serializer);
        publish(store, "20260913T020000Z", "SCHEDULED");
        publish(store, "20260914T020000Z", "SCHEDULED");

        assertThat(store.latest().orElseThrow().trigger()).isEqualTo("SCHEDULED");
        assertThat(store.latest().orElseThrow().dataFingerprint()).isEqualTo("fp-20260914T020000Z");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.deleteGeneration("20260914T020000Z"))
                .isInstanceOf(IOException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.deleteGeneration("../staging"))
                .isInstanceOf(IOException.class);

        store.deleteGeneration("20260913T020000Z");

        assertThat(store.listGenerations()).extracting(s -> s.generation()).containsExactly("20260914T020000Z");
    }

    @Test
    void scheduledRunIsRememberedAcrossRestarts() throws IOException {
        FilesystemExportStore store = new FilesystemExportStore(dir.toString(), serializer);
        assertThat(store.scheduledRun()).isEmpty();
        var run = new dev.aegis.portability.application.ports.ExportStore.ScheduledRun(
                Instant.parse("2026-09-14T00:30:00Z"), Instant.parse("2026-09-13T00:30:00Z"));

        store.recordScheduledRun(run);

        assertThat(new FilesystemExportStore(dir.toString(), serializer).scheduledRun()).contains(run);
    }

    @Test
    void canonicalJsonDoesNotDependOnMapOrder() {
        Map<String, String> one = new java.util.LinkedHashMap<>();
        one.put("b", "2");
        one.put("a", "1");
        Map<String, String> other = new java.util.LinkedHashMap<>();
        other.put("a", "1");
        other.put("b", "2");

        assertThat(serializer.toCanonicalJson(one)).isEqualTo(serializer.toCanonicalJson(other));
    }

    private void publish(FilesystemExportStore store, String generation, String trigger) throws IOException {
        var staging = store.beginGeneration(generation);
        staging.write(ExportFormat.MANIFEST, serializer.toJson(new Manifest(11, generation,
                Instant.parse("2026-09-13T10:00:00Z"), "test", 0, 0, 1, 0, List.of(), Map.of(), trigger,
                "fp-" + generation)));
        staging.publish();
    }
}
