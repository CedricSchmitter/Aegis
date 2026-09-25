package dev.aegis.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.aegis.bootstrap.support.ApiClient;
import dev.aegis.bootstrap.support.FakePaperlessArchive;
import dev.aegis.bootstrap.support.PostgresIT;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;

/**
 * Photos of paper documents (issue #80, ADR-0011) through the HTTP API: the type comes from the content, the photo
 * stays the original with its own type, the archive's searchable PDF is served separately (and offered for scanned
 * PDFs, ADR-0016), duplicates are found by checksum and unsupported or damaged files are refused before anything is
 * saved.
 */
class PhotoCaptureIT extends PostgresIT {
    @LocalServerPort
    int port;

    @Autowired
    FakePaperlessArchive archive;

    @Test
    void aPhotoIsSavedArchivedAndServedWithItsOwnTypeAndAsSearchablePdf() throws Exception {
        ApiClient anna = new ApiClient(port);
        assertThat(anna.login(ANNA, PASSWORD)).isTrue();
        ApiClient ben = new ApiClient(port);
        assertThat(ben.login(BEN, PASSWORD)).isTrue();
        byte[] photo = uniqueJpeg("letter");

        // The test client declares every file part as application/pdf; only the content decides.
        ApiClient.Response uploaded = anna.upload("/api/documents", "IMG_20260922_101500.jpg", photo,
                List.of("HOUSE"));
        assertThat(uploaded.status()).as(uploaded.body()).isEqualTo(201);
        JsonNode document = anna.json(uploaded).path("document");
        String id = document.path("id").asText();
        assertThat(document.path("contentType").asText()).isEqualTo("image/jpeg");
        assertThat(document.path("title").asText()).isEqualTo("IMG 20260922 101500");
        assertThat(document.path("originalFilename").asText()).isEqualTo("IMG_20260922_101500.jpg");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(anna.json(anna.get(
                "/api/documents/" + id)).path("processing").path("state").asText()).isEqualTo("ARCHIVED"));

        ApiClient.Response original = ben.get("/api/documents/" + id + "/original");
        assertThat(original.status()).isEqualTo(200);
        assertThat(original.header("Content-Type")).isEqualTo("image/jpeg");
        assertThat(original.header("X-Aegis-Original-Source")).isEqualTo("ARCHIVE");
        // The friendly name leaves out long digit runs such as camera timestamps; the extension follows the content.
        assertThat(original.header("Content-Disposition")).contains("_" + id.substring(0, 8) + ".jpg");
        assertThat(original.bytes()).isEqualTo(photo);

        ApiClient.Response searchable = ben.get("/api/documents/" + id + "/archive-pdf?inline=true");
        assertThat(searchable.status()).as(searchable.body()).isEqualTo(200);
        assertThat(searchable.header("Content-Type")).isEqualTo("application/pdf");
        assertThat(searchable.header("Content-Disposition")).startsWith("inline")
                .contains("_" + id.substring(0, 8) + ".pdf");
        assertThat(new String(searchable.bytes(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");

        // Sharing the same photo again finds the existing document instead of creating a copy.
        ApiClient.Response again = ben.upload("/api/documents", "image.jpg", photo, List.of());
        assertThat(again.status()).isEqualTo(200);
        assertThat(ben.json(again).path("duplicate").asBoolean()).isTrue();
        assertThat(ben.json(again).path("document").path("id").asText()).isEqualTo(id);
    }

    @Test
    void unsupportedOrDamagedFilesAreRefusedBeforeAnythingIsSaved() throws Exception {
        ApiClient anna = new ApiClient(port);
        assertThat(anna.login(ANNA, PASSWORD)).isTrue();
        long stagedBefore = stagedFiles();
        byte[] damagedPng = {(byte) 0x89, 'P', 'N', 'G', '\n', 0x1A, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R', 0};
        byte[] genericHeif = {0, 0, 0, 24, 'f', 't', 'y', 'p', 'm', 'i', 'f', '1', 0, 0, 0, 0, 'm', 'i', 'f', '1',
                'h', 'e', 'i', 'c'};

        Map<String, byte[]> refusedFiles = new LinkedHashMap<>();
        refusedFiles.put("Foto.jpg", "GIF89a synthetic".getBytes(StandardCharsets.US_ASCII));
        refusedFiles.put("Scan.png", damagedPng);
        refusedFiles.put("Foto.heif", genericHeif);
        refusedFiles.put("Brief.pdf", "<html>not a letter</html>".getBytes(StandardCharsets.US_ASCII));

        refusedFiles.forEach((name, content) -> {
            ApiClient.Response refused = anna.upload("/api/documents", name, content, List.of());
            assertThat(refused.status()).as(name).isEqualTo(400);
            assertThat(anna.json(refused).path("code").asText()).isEqualTo("document.unsupportedType");
        });
        assertThat(stagedFiles()).as("no original is kept for a refused file").isEqualTo(stagedBefore);
    }

    @Test
    void aBornDigitalPdfHasNoSeparateArchivePdf() throws Exception {
        ApiClient anna = new ApiClient(port);
        assertThat(anna.login(ANNA, PASSWORD)).isTrue();
        byte[] pdf = ("%PDF-1.4 photo capture " + System.nanoTime()).getBytes(StandardCharsets.US_ASCII);
        ApiClient.Response uploaded = anna.upload("/api/documents", "Vertrag.pdf", pdf, List.of());
        assertThat(uploaded.status()).as(uploaded.body()).isEqualTo(201);
        String id = anna.json(uploaded).path("document").path("id").asText();
        JsonNode checked = awaitTextChecked(anna, id);
        assertThat(checked.path("archivePdfAvailable").asBoolean(true)).isFalse();

        ApiClient.Response none = anna.get("/api/documents/" + id + "/archive-pdf");

        assertThat(none.status()).isEqualTo(404);
        assertThat(anna.json(none).path("code").asText()).isEqualTo("archivePdf.notFound");
    }

    @Test
    void aScannedPdfWithoutTextLayerOffersTheArchivesSearchablePdf() throws Exception {
        ApiClient anna = new ApiClient(port);
        assertThat(anna.login(ANNA, PASSWORD)).isTrue();
        // Pages assembled from camera photos (ADR-0016) carry no text layer; the archive recognizes their text.
        byte[] scan = ("%PDF-1.4 scanned pages " + System.nanoTime()).getBytes(StandardCharsets.US_ASCII);
        archive.scannedPdf(scan, "Sehr geehrte Frau Muster, anbei die Rechnung für September.");
        ApiClient.Response uploaded = anna.upload("/api/documents", "Scanrechnung September.pdf", scan, List.of());
        assertThat(uploaded.status()).as(uploaded.body()).isEqualTo(201);
        String id = anna.json(uploaded).path("document").path("id").asText();
        assertThat(anna.json(uploaded).path("document").path("archivePdfAvailable").asBoolean(true))
                .as("not archived yet").isFalse();

        JsonNode checked = awaitTextChecked(anna, id);

        assertThat(checked.path("contentType").asText()).isEqualTo("application/pdf");
        assertThat(checked.path("archivePdfAvailable").asBoolean()).isTrue();
        assertThat(listed(anna, id).path("archivePdfAvailable").asBoolean()).as("in the list as well").isTrue();
        ApiClient.Response searchable = anna.get("/api/documents/" + id + "/archive-pdf?inline=true");
        assertThat(searchable.status()).as(searchable.body()).isEqualTo(200);
        assertThat(searchable.header("Content-Type")).isEqualTo("application/pdf");
        assertThat(searchable.header("Content-Disposition")).startsWith("inline");
        ApiClient.Response original = anna.get("/api/documents/" + id + "/original");
        assertThat(original.bytes()).as("the scan stays the original").isEqualTo(scan);
    }

    /** Waits until the document is archived and its text check measured, and returns it. */
    private static JsonNode awaitTextChecked(ApiClient client, String id) {
        JsonNode[] document = new JsonNode[1];
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            document[0] = client.json(client.get("/api/documents/" + id));
            assertThat(document[0].path("processing").path("state").asText()).isEqualTo("ARCHIVED");
            assertThat(document[0].path("textRecognition").path("state").asText())
                    .isIn("NONE", "LITTLE", "RECOGNIZED");
        });
        return document[0];
    }

    private static JsonNode listed(ApiClient client, String id) {
        JsonNode page = client.json(client.get("/api/documents?query=Scanrechnung"));
        for (JsonNode item : page.path("items")) {
            if (item.path("id").asText().equals(id)) {
                return item;
            }
        }
        throw new AssertionError("document " + id + " is not listed");
    }

    /** A minimal JPEG (SOI, APP0) with a unique comment, so every run stores new content. */
    private static byte[] uniqueJpeg(String label) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0,
                1, 1, 0, 0, 72, 0, 72, 0, 0});
        byte[] comment = (label + " " + System.nanoTime()).getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xFE, 0, (byte) (comment.length + 2)});
        out.writeBytes(comment);
        out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xD9});
        return out.toByteArray();
    }

    private static long stagedFiles() throws Exception {
        try (Stream<Path> files = Files.list(DATA.resolve("ingress"))) {
            return files.filter(Files::isRegularFile).count();
        }
    }
}
