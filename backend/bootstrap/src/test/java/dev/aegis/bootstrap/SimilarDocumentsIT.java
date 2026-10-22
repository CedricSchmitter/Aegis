package dev.aegis.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.aegis.bootstrap.support.ApiClient;
import dev.aegis.bootstrap.support.FakePaperlessArchive;
import dev.aegis.bootstrap.support.PostgresIT;
import dev.aegis.documents.application.ArchiveTextService;
import dev.aegis.documents.domain.text.ArchiveTextCheckRepository;
import dev.aegis.shared.DocumentId;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * Near-duplicate hints (ADR-0028) against PostgreSQL and the HTTP API: the fingerprint is stored with the text check
 * after archival, the document page lists the other document, the review inbox shows one card for the pair, trashing
 * one of them removes the hint, and fingerprints missing from older checks are made by the sweep.
 */
class SimilarDocumentsIT extends PostgresIT {
    @LocalServerPort
    int port;

    @Autowired
    FakePaperlessArchive archive;

    @Autowired
    ArchiveTextService textChecks;

    @Autowired
    ArchiveTextCheckRepository checks;

    @Autowired
    JdbcClient jdbc;

    /** A letter no other test uploads, so its pairs belong to this test alone. */
    private static String letter() {
        long unique = System.nanoTime();
        return """
                Wasserversorgung Musterstadt, Kundennummer %d, Musterstadt den 12.10.2026
                Sehr geehrte Familie Beispiel, mit diesem Schreiben bestätigen wir den Zählerwechsel an Ihrem
                Hausanschluss Musterweg 7 am 08.10.2026. Der alte Zähler Nummer %d zeigte zuletzt 1.284 Kubikmeter,
                der neue Zähler Nummer %d startet bei null. Die Abschläge bleiben unverändert bei 38 Euro im Monat.
                Bitte bewahren Sie dieses Schreiben für Ihre Unterlagen auf. Mit freundlichen Grüßen
                """.formatted(unique, unique + 1, unique + 2);
    }

    @Test
    void theSameLetterUploadedTwiceIsHintedOnBothDocumentsAndInTheInboxUntilOneIsTrashed() {
        ApiClient anna = login(ANNA);
        ApiClient ben = login(BEN);
        String text = letter();
        byte[] scan = uniqueJpeg("scan");
        byte[] photo = uniqueJpeg("photo");
        byte[] unrelated = uniqueJpeg("unrelated");
        archive.ocrText(scan, text);
        archive.ocrText(photo, text.replace("\n", " ").replace("Unterlagen", "Unlerlagen"));
        archive.ocrText(unrelated, letter());

        String first = upload(anna, scan);
        String second = upload(ben, photo);
        String other = upload(anna, unrelated);
        for (String id : List.of(first, second, other)) {
            awaitTextState(anna, id, "RECOGNIZED");
        }

        assertThat(ids(ben.json(ben.get("/api/documents/" + first + "/similar")))).containsExactly(second);
        assertThat(ids(anna.json(anna.get("/api/documents/" + second + "/similar")))).containsExactly(first);
        assertThat(ids(anna.json(anna.get("/api/documents/" + other + "/similar")))).isEmpty();

        JsonNode card = openSimilarCard(ben, first).orElseThrow();
        assertThat(card.path("category").asText()).isEqualTo("DUPLICATE");
        assertThat(card.path("titleKey").asText()).isEqualTo("review.similarDocument");
        assertThat(card.path("linkType").asText()).isEqualTo("DOCUMENT");
        assertThat(card.path("linkId").asText()).isEqualTo(first);
        assertThat(card.path("params").path("otherTitle").asText())
                .isEqualTo(anna.json(anna.get("/api/documents/" + second)).path("title").asText());
        assertThat(card.path("actions").isEmpty()).isTrue();
        assertThat(openSimilarCard(ben, second)).as("one card for the pair").isEmpty();

        JsonNode trashed = anna.json(anna.postJson("/api/documents/" + second + "/trash",
                "{\"expectedVersion\":" + anna.json(anna.get("/api/documents/" + second)).path("version").asLong()
                        + "}"));
        assertThat(trashed.path("id").asText()).isEqualTo(second);
        assertThat(ids(anna.json(anna.get("/api/documents/" + first + "/similar")))).isEmpty();
        assertThat(ids(anna.json(anna.get("/api/documents/" + second + "/similar")))).isEmpty();
        assertThat(openSimilarCard(ben, first)).as("trashing one supersedes the card").isEmpty();
    }

    @Test
    void fingerprintsMissingFromOlderChecksAreMadeByTheSweep() {
        ApiClient anna = login(ANNA);
        String text = letter();
        byte[] scan = uniqueJpeg("older-scan");
        byte[] photo = uniqueJpeg("older-photo");
        archive.ocrText(scan, text);
        archive.ocrText(photo, text);
        String first = upload(anna, scan);
        String second = upload(anna, photo);
        awaitTextState(anna, first, "RECOGNIZED");
        awaitTextState(anna, second, "RECOGNIZED");

        // As V2_11 leaves checks measured before fingerprints existed: count and archive version, no fingerprint.
        jdbc.sql("UPDATE document_text_checks SET text_fingerprint = NULL WHERE document_id = ?")
                .param(UUID.fromString(first)).update();
        assertThat(ids(anna.json(anna.get("/api/documents/" + second + "/similar")))).isEmpty();
        assertThat(anna.json(anna.get("/api/documents/" + first)).path("textRecognition").path("state").asText())
                .isEqualTo("RECOGNIZED");
        assertThat(checks.findDue(Instant.now(), 1_000)).contains(DocumentId.of(first));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            textChecks.sweep();
            assertThat(ids(anna.json(anna.get("/api/documents/" + second + "/similar")))).containsExactly(first);
        });
        assertThat(checks.findDue(Instant.now(), 1_000)).doesNotContain(DocumentId.of(first));
    }

    private ApiClient login(String username) {
        ApiClient client = new ApiClient(port);
        assertThat(client.login(username, PASSWORD)).isTrue();
        return client;
    }

    private static List<String> ids(JsonNode documents) {
        return StreamSupport.stream(documents.spliterator(), false).map(item -> item.path("id").asText()).toList();
    }

    private static String upload(ApiClient client, byte[] photo) {
        ApiClient.Response uploaded = client.upload("/api/documents", "IMG_" + System.nanoTime() + ".jpg", photo,
                List.of("HOUSE"));
        assertThat(uploaded.status()).as(uploaded.body()).isEqualTo(201);
        return client.json(uploaded).path("document").path("id").asText();
    }

    private static void awaitTextState(ApiClient client, String id, String state) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(client.json(client.get(
                "/api/documents/" + id)).path("textRecognition").path("state").asText()).isEqualTo(state));
    }

    private static Optional<JsonNode> openSimilarCard(ApiClient client, String documentId) {
        JsonNode items = client.json(client.get("/api/reviews?status=OPEN"));
        return StreamSupport.stream(items.spliterator(), false)
                .filter(item -> "SIMILAR_DOCUMENT".equals(item.path("reason").asText()))
                .filter(item -> documentId.equals(item.path("sourceId").asText())).findFirst();
    }

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
}
