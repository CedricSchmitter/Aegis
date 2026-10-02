package dev.aegis.portability.adapters.json;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.portability.application.ExportFormat.DerivationEntry;
import dev.aegis.portability.application.ExportFormat.DerivationRangeEntry;
import dev.aegis.portability.application.ExportFormat.DocumentEntry;
import dev.aegis.portability.application.ExportFormat.DocumentsFile;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class JacksonExportSerializerTest {
    private static final Instant NOW = Instant.parse("2026-10-02T09:00:00Z");
    private final JacksonExportSerializer serializer = new JacksonExportSerializer(JsonMapper.builder().build());

    @Test
    void aDocumentOfASchemaTenFileHasNoDerivation() {
        String schemaTen = """
                {"schemaVersion": 10, "exportedAt": "2026-09-30T08:00:00Z", "documents": [
                  {"id": "dddddddd-0000-4000-8000-000000000002", "title": "Brief", "titleOrigin": "FILENAME",
                   "originalFilename": "brief.pdf", "filename": "Brief_dddddddd.pdf",
                   "contentType": "application/pdf", "sizeBytes": 3, "checksumSha256": "abc",
                   "uploadedBy": "aaaaaaaa-0000-4000-8000-000000000001", "uploadedAt": "2026-09-30T08:00:00Z",
                   "subjects": [], "processingState": "ARCHIVED", "paperlessDocumentId": 4,
                   "archivedAt": "2026-09-30T08:00:00Z", "originalPath": "documents/originals/Brief_dddddddd.pdf",
                   "classification": null, "analysis": null, "trash": null}]}
                """;

        DocumentsFile file = serializer.fromJson(schemaTen.getBytes(StandardCharsets.UTF_8), DocumentsFile.class);

        assertThat(file.documents()).singleElement().satisfies(document -> {
            assertThat(document.title()).isEqualTo("Brief");
            assertThat(document.derivedFrom()).isNull();
        });
    }

    @Test
    void derivationsSurviveTheRoundTripIncludingScannedPagesAndDeletedSources() {
        DerivationEntry derivation = new DerivationEntry("ffffffff-0000-4000-8000-000000000004",
                "aaaaaaaa-0000-4000-8000-000000000001", NOW, List.of(
                        new DerivationRangeEntry(1, 2, "DOCUMENT", "cccccccc-0000-4000-8000-000000000001", 3, 4, 90),
                        new DerivationRangeEntry(3, 3, "SCAN", null, 1, 1, 0),
                        new DerivationRangeEntry(4, 4, "DOCUMENT", null, 7, 7, 0)));
        DocumentEntry document = new DocumentEntry("dddddddd-0000-4000-8000-000000000002", "Teil 2", "FILENAME",
                "teil2.pdf", "Teil2_dddddddd.pdf", "application/pdf", 3, "abc",
                "aaaaaaaa-0000-4000-8000-000000000001", NOW, List.of(), "ARCHIVED", 5, NOW, null, null, null, null,
                derivation);

        byte[] json = serializer.toJson(new DocumentsFile(11, NOW, List.of(document)));

        assertThat(new String(json, StandardCharsets.UTF_8)).contains("\"derivedFrom\"")
                .contains("\"sourceDocumentId\" : \"cccccccc-0000-4000-8000-000000000001\"");
        assertThat(serializer.fromJson(json, DocumentsFile.class).documents()).containsExactly(document);
    }
}
