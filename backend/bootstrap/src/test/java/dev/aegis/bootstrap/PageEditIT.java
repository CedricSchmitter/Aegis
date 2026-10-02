package dev.aegis.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.aegis.bootstrap.support.ApiClient;
import dev.aegis.bootstrap.support.ApiClient.Part;
import dev.aegis.bootstrap.support.FakePaperlessArchive;
import dev.aegis.bootstrap.support.PostgresIT;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * The page editor (ADR-0024) through the HTTP API against PostgreSQL and the real job scheduler: splitting
 * a scan of two letters, merging a photo with newly scanned pages, repeated and concurrent requests that create every
 * document once, refused sources, and source relationships that survive the permanent deletion of a source.
 */
class PageEditIT extends PostgresIT {
    @LocalServerPort
    int port;

    @Autowired
    FakePaperlessArchive archive;

    @Autowired
    JdbcClient jdbc;

    @Test
    void aScanOfTwoLettersIsSplitIntoTwoDocumentsThatKnowWhereTheyCameFrom() throws Exception {
        ApiClient anna = login(ANNA);
        ApiClient ben = login(BEN);
        String marker = "split-" + System.nanoTime();
        String source = upload(anna, "Zwei Briefe.pdf", pdf(marker, 3, null));

        JsonNode pages = anna.json(anna.get("/api/documents/" + source + "/pages"));
        assertThat(pages.path("pages")).hasSize(3);
        assertThat(pages.path("refusal").isNull()).isTrue();
        ApiClient.Response preview = ben.get("/api/documents/" + source + "/pages/2/preview");
        assertThat(preview.status()).as(preview.body()).isEqualTo(200);
        assertThat(preview.header("Content-Type")).isEqualTo("image/jpeg");
        assertThat(ben.get("/api/documents/" + source + "/pages/3/preview").status()).isEqualTo(404);

        String id = UUID.randomUUID().toString();
        String plan = plan(id, output("Stadtwerke", false, page(source, 0, 0), page(source, 1, 0)),
                output("Versicherung", true, page(source, 2, 180)));
        ApiClient.Response accepted = anna.multipart("/api/page-edits", List.of(json("plan", plan)));
        assertThat(accepted.status()).as(accepted.body()).isEqualTo(202);
        JsonNode done = awaitFinished(anna, id);
        assertThat(done.path("state").asText()).as(done.toString()).isEqualTo("COMPLETED");
        assertThat(done.path("results")).hasSize(2);
        String first = done.path("results").get(0).path("documentId").asText();
        String second = done.path("results").get(1).path("documentId").asText();

        JsonNode firstDocument = anna.json(anna.get("/api/documents/" + first));
        assertThat(firstDocument.path("title").asText()).isEqualTo("Stadtwerke");
        assertThat(firstDocument.path("titleOrigin").asText()).isEqualTo("FILENAME");
        assertThat(firstDocument.path("uploadedBy").asText()).isEqualTo(memberId(anna));
        assertThat(anna.json(anna.get("/api/documents/" + second)).path("titleOrigin").asText())
                .isEqualTo("MANUAL");
        try (PDDocument part = Loader.loadPDF(anna.get("/api/documents/" + first + "/original").bytes())) {
            assertThat(part.getNumberOfPages()).isEqualTo(2);
            assertThat(new PDFTextStripper().getText(part)).contains(marker + " page 1")
                    .contains(marker + " page 2").doesNotContain(marker + " page 3");
        }
        try (PDDocument part = Loader.loadPDF(anna.get("/api/documents/" + second + "/original").bytes())) {
            assertThat(part.getPage(0).getRotation()).isEqualTo(180);
        }

        JsonNode origin = ben.json(ben.get("/api/documents/" + first + "/derivations")).path("origin");
        assertThat(origin.path("createdBy").asText()).isEqualTo(memberId(anna));
        assertThat(origin.path("ranges")).hasSize(1);
        assertThat(origin.path("ranges").get(0).path("sourceDocumentId").asText()).isEqualTo(source);
        assertThat(origin.path("ranges").get(0).path("lastSourcePage").asInt()).isEqualTo(2);
        assertThat(origin.path("ranges").get(0).path("sourceState").asText()).isEqualTo("AVAILABLE");
        JsonNode derived = ben.json(ben.get("/api/documents/" + source + "/derivations")).path("derived");
        assertThat(derived).hasSize(2);
        assertThat(ben.json(ben.get("/api/documents/" + source)).path("trash").isNull()).isTrue();

        // A request repeated after a lost answer, for example, returns the same operation and creates nothing new.
        long documents = documentCount();
        ApiClient.Response repeated = anna.multipart("/api/page-edits", List.of(json("plan", plan)));
        assertThat(repeated.status()).isEqualTo(202);
        assertThat(anna.json(repeated).path("results")).hasSize(2);
        assertThat(documentCount()).isEqualTo(documents);
        ApiClient.Response changed = anna.multipart("/api/page-edits", List.of(json("plan",
                plan(id, output("Stadtwerke", false, page(source, 0, 0))))));
        assertThat(changed.status()).isEqualTo(400);
        assertThat(anna.json(changed).path("code").asText()).isEqualTo("pageEdit.conflict");
    }

    @Test
    void aPhotoAndNewlyScannedPagesAreMergedIntoOneA4Document() throws Exception {
        ApiClient anna = login(ANNA);
        byte[] photo = jpeg("annex");
        archive.rendition(photo, rendition());
        String photoId = upload(anna, "IMG_20261002_0815.jpg", photo);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(anna.json(anna.get(
                "/api/documents/" + photoId)).path("processing").path("state").asText()).isEqualTo("ARCHIVED"));
        assertThat(anna.json(anna.get("/api/documents/" + photoId + "/pages")).path("sourceKind").asText())
                .isEqualTo("PHOTO");
        String marker = "scan-" + System.nanoTime();
        byte[] scan = pdf(marker, 1, null);

        String id = UUID.randomUUID().toString();
        ApiClient.Response accepted = anna.multipart("/api/page-edits", List.of(
                json("plan", plan(id, output("Vertrag mit Anlage", false, page(photoId, 0, 0),
                        "{\"source\":\"SCAN\",\"pageIndex\":0,\"rotation\":0}"))),
                new Part("scan", "scan.pdf", "application/pdf", scan)));
        assertThat(accepted.status()).as(accepted.body()).isEqualTo(202);

        JsonNode done = awaitFinished(anna, id);
        assertThat(done.path("state").asText()).as(done.toString()).isEqualTo("COMPLETED");
        String merged = done.path("results").get(0).path("documentId").asText();
        try (PDDocument document = Loader.loadPDF(anna.get("/api/documents/" + merged + "/original").bytes())) {
            assertThat(document.getNumberOfPages()).isEqualTo(2);
            PDRectangle photoPage = document.getPage(0).getMediaBox();
            assertThat(Math.max(photoPage.getWidth(), photoPage.getHeight())).isBetween(841f, 843f);
            assertThat(new PDFTextStripper().getText(document)).contains(marker + " page 1");
        }
        JsonNode ranges = anna.json(anna.get("/api/documents/" + merged + "/derivations")).path("origin")
                .path("ranges");
        assertThat(ranges.get(0).path("sourceKind").asText()).isEqualTo("DOCUMENT");
        assertThat(ranges.get(1).path("sourceKind").asText()).isEqualTo("SCAN");
        assertThat(scanFiles()).as("the scanned pages exist only in the new document").isEmpty();
    }

    @Test
    void concurrentRequestsWithTheSameIdCreateOneOperation() throws Exception {
        ApiClient anna = login(ANNA);
        String source = upload(anna, "Gleichzeitig.pdf", pdf("concurrent-" + System.nanoTime(), 2, null));
        String id = UUID.randomUUID().toString();
        String plan = plan(id, output("Teil", false, page(source, 1, 0)));

        List<CompletableFuture<ApiClient.Response>> requests = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            requests.add(CompletableFuture.supplyAsync(() -> anna.multipart("/api/page-edits",
                    List.of(json("plan", plan)))));
        }
        for (CompletableFuture<ApiClient.Response> request : requests) {
            assertThat(request.get().status()).as(request.get().body()).isEqualTo(202);
        }

        assertThat(awaitFinished(anna, id).path("results")).hasSize(1);
        assertThat(jdbc.sql("SELECT count(*) FROM document_page_edits WHERE id = ?").param(UUID.fromString(id))
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM document_derivations WHERE source_document_id = ?")
                .param(UUID.fromString(source)).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void anEncryptedPdfIsRefusedWithAReasonTheAppCanShow() throws Exception {
        ApiClient anna = login(ANNA);
        String locked = upload(anna, "Gesperrt.pdf", pdf("locked-" + System.nanoTime(), 1, "secret"));

        JsonNode pages = anna.json(anna.get("/api/documents/" + locked + "/pages"));
        assertThat(pages.path("refusal").asText()).isEqualTo("pageEdit.source.encrypted");
        assertThat(pages.path("pages")).isEmpty();
        ApiClient.Response refused = anna.multipart("/api/page-edits", List.of(json("plan",
                plan(UUID.randomUUID().toString(), output("A", false, page(locked, 0, 0))))));
        assertThat(refused.status()).isEqualTo(400);
        assertThat(anna.json(refused).path("code").asText()).isEqualTo("pageEdit.source.encrypted");
    }

    @Test
    void aPermanentlyDeletedSourceStaysVisibleAsDeletedInTheOrigin() throws Exception {
        ApiClient anna = login(ANNA);
        String source = upload(anna, "Wird gelöscht.pdf", pdf("deleted-" + System.nanoTime(), 2, null));
        String id = UUID.randomUUID().toString();
        anna.multipart("/api/page-edits", List.of(json("plan", plan(id, output("Rest", false,
                page(source, 1, 0))))));
        String part = awaitFinished(anna, id).path("results").get(0).path("documentId").asText();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(anna.json(anna.get(
                "/api/documents/" + source)).path("processing").path("state").asText()).isEqualTo("ARCHIVED"));

        JsonNode trashed = anna.json(anna.postJson("/api/documents/" + source + "/trash", version(anna, source)));
        assertThat(anna.postJson("/api/documents/" + source + "/purge", "{\"expectedVersion\":"
                + trashed.path("version").asLong() + "}").status()).isEqualTo(202);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(anna.get("/api/documents/" + source)
                .status()).isEqualTo(404));

        JsonNode range = anna.json(anna.get("/api/documents/" + part + "/derivations")).path("origin")
                .path("ranges").get(0);
        assertThat(range.path("sourceState").asText()).isEqualTo("DELETED");
        assertThat(range.path("sourceDocumentId").isNull()).isTrue();
        assertThat(range.path("firstSourcePage").asInt()).isEqualTo(2);
    }

    private ApiClient login(String username) {
        ApiClient client = new ApiClient(port);
        assertThat(client.login(username, PASSWORD)).isTrue();
        return client;
    }

    private static String upload(ApiClient client, String name, byte[] content) {
        ApiClient.Response uploaded = client.upload("/api/documents", name, content, List.of("HOUSE"));
        assertThat(uploaded.status()).as(uploaded.body()).isEqualTo(201);
        return client.json(uploaded).path("document").path("id").asText();
    }

    private static JsonNode awaitFinished(ApiClient client, String id) {
        JsonNode[] last = new JsonNode[1];
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            last[0] = client.json(client.get("/api/page-edits/" + id));
            assertThat(last[0].path("state").asText()).isNotEqualTo("REQUESTED");
        });
        return last[0];
    }

    private static Part json(String name, String content) {
        return new Part(name, null, "application/json", content.getBytes(StandardCharsets.UTF_8));
    }

    private static String plan(String id, String... outputs) {
        return "{\"id\":\"" + id + "\",\"outputs\":[" + String.join(",", outputs) + "]}";
    }

    private static String output(String title, boolean chosen, String... pages) {
        return "{\"title\":\"" + title + "\",\"titleChosen\":" + chosen + ",\"categoryId\":null,"
                + "\"subjects\":[{\"kind\":\"HOUSE\",\"memberId\":null}],\"pages\":[" + String.join(",", pages)
                + "]}";
    }

    private static String page(String document, int index, int rotation) {
        return "{\"source\":\"DOCUMENT\",\"documentId\":\"" + document + "\",\"pageIndex\":" + index
                + ",\"rotation\":" + rotation + "}";
    }

    private static String version(ApiClient client, String document) {
        return "{\"expectedVersion\":" + client.json(client.get("/api/documents/" + document)).path("version")
                .asLong() + "}";
    }

    private static String memberId(ApiClient client) {
        return client.json(client.get("/api/auth/session")).path("member").path("id").asText();
    }

    private long documentCount() {
        return jdbc.sql("SELECT count(*) FROM documents").query(Long.class).single();
    }

    private static List<Path> scanFiles() throws IOException {
        Path directory = DATA.resolve("ingress").resolve("page-edits");
        try (Stream<Path> files = Files.list(directory)) {
            return files.toList();
        }
    }

    /** A real PDF with one line of text per page; encrypted with {@code password} when it is not null. */
    private static byte[] pdf(String marker, int pages, String password) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (int i = 1; i <= pages; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 18);
                    content.newLineAtOffset(72, 700);
                    content.showText(marker + " page " + i);
                    content.endText();
                }
            }
            if (password != null) {
                document.protect(new StandardProtectionPolicy("owner-" + password, password,
                        new AccessPermission()));
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            document.save(bytes);
            return bytes.toByteArray();
        }
    }

    /** What the archive renders from a phone photo: one page sized from 72 dpi metadata. */
    private static byte[] rendition() throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(3024, 4032));
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.addRect(200, 200, 2600, 3600);
                content.fill();
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            document.save(bytes);
            return bytes.toByteArray();
        }
    }

    private static byte[] jpeg(String name) {
        byte[] tail = (name + System.nanoTime()).getBytes(StandardCharsets.US_ASCII);
        byte[] bytes = new byte[4 + tail.length];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        bytes[3] = (byte) 0xE0;
        System.arraycopy(tail, 0, bytes, 4, tail.length);
        return bytes;
    }
}
