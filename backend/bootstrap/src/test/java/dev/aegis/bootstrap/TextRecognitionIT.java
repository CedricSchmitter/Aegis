package dev.aegis.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import dev.aegis.bootstrap.support.ApiClient;
import dev.aegis.bootstrap.support.FakePaperlessArchive;
import dev.aegis.bootstrap.support.PostgresIT;
import dev.aegis.documents.application.ArchiveTextService;
import dev.aegis.documents.domain.text.ArchiveTextCheck;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * Whether the archive recognized text (ADR-0012) against PostgreSQL and the HTTP API: measured right after
 * archival without AI, shown on the document and in the list, a review card for documents without text, archival
 * unaffected when the text cannot be read, and a bounded sweep that catches up on documents archived before.
 */
class TextRecognitionIT extends PostgresIT {
    private static final String LETTER = "Sehr geehrte Frau Muster, anbei erhalten Sie die Rechnung für September "
            + "mit der Kundennummer 4711.";

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

    @Test
    void aPhotoWithoutRecognizedTextIsFlaggedInTheDocumentTheListAndTheReviewInbox() {
        ApiClient anna = login(ANNA);
        ApiClient ben = login(BEN);
        byte[] skewed = uniqueJpeg("skewed");
        byte[] straight = uniqueJpeg("straight");
        archive.ocrText(skewed, " \n\f ");
        archive.ocrText(straight, LETTER);

        String blank = upload(anna, skewed);
        String readable = upload(anna, straight);

        JsonNode noText = awaitTextState(ben, blank, "NONE");
        assertThat(noText.path("processing").path("state").asText()).isEqualTo("ARCHIVED");
        assertThat(noText.path("textRecognition").path("recognizedCharacters").asInt()).isZero();
        assertThat(noText.path("textRecognition").path("checkedAt").isTextual()).isTrue();
        JsonNode recognized = awaitTextState(ben, readable, "RECOGNIZED");
        assertThat(recognized.path("textRecognition").path("recognizedCharacters").asInt()).isGreaterThan(50);

        JsonNode list = ben.json(ben.get("/api/documents?size=100"));
        assertThat(item(list.path("items"), blank).path("textRecognition").path("state").asText()).isEqualTo("NONE");
        assertThat(item(list.path("items"), readable).path("textRecognition").path("state").asText())
                .isEqualTo("RECOGNIZED");

        JsonNode card = openNoTextCard(ben, blank).orElseThrow();
        assertThat(card.path("category").asText()).isEqualTo("PROCESSING_FAILURE");
        assertThat(card.path("titleKey").asText()).isEqualTo("review.noText");
        assertThat(card.path("params").path("title").asText()).isEqualTo(noText.path("title").asText());
        assertThat(card.path("linkType").asText()).isEqualTo("DOCUMENT");
        assertThat(card.path("linkId").asText()).isEqualTo(blank);
        assertThat(card.path("actions").isEmpty()).isTrue();
        assertThat(openNoTextCard(ben, readable)).isEmpty();

        // Renaming the document does not bring a dismissed card back: the archive document is its source version.
        ApiClient.Response dismissed = anna.postJson("/api/reviews/" + card.path("id").asText() + "/dismiss",
                "{\"expectedVersion\":" + card.path("version").asLong() + "}");
        assertThat(dismissed.status()).as(dismissed.body()).isEqualTo(200);
        ApiClient.Response renamed = anna.putJson("/api/documents/" + blank + "/title",
                "{\"title\":\"Brief schief\",\"expectedVersion\":" + noText.path("version").asLong() + "}");
        assertThat(renamed.status()).as(renamed.body()).isEqualTo(200);
        assertThat(openNoTextCard(ben, blank)).isEmpty();
    }

    @Test
    void archivalSucceedsWhenTheTextCannotBeReadAndTheCheckCatchesUpLater() {
        ApiClient anna = login(ANNA);
        byte[] photo = uniqueJpeg("dark");
        archive.ocrText(photo, "");
        archive.contentAvailable = false;
        String id;
        try {
            id = upload(anna, photo);
            JsonNode unavailable = awaitTextState(anna, id, "UNAVAILABLE");
            assertThat(unavailable.path("processing").path("state").asText()).isEqualTo("ARCHIVED");
            assertThat(unavailable.path("processing").path("lastErrorCode").isNull()).isTrue();
            assertThat(Instant.parse(unavailable.path("textRecognition").path("nextAttemptAt").asText()))
                    .isAfter(Instant.parse(unavailable.path("textRecognition").path("checkedAt").asText()));
            assertThat(openNoTextCard(anna, id)).isEmpty();
        } finally {
            archive.contentAvailable = true;
        }

        assertThat(textChecks.check(DocumentId.of(id))).isEqualTo(ArchiveTextService.Outcome.MEASURED);
        assertThat(anna.json(anna.get("/api/documents/" + id)).path("textRecognition").path("state").asText())
                .isEqualTo("NONE");
        assertThat(openNoTextCard(anna, id)).isPresent();
    }

    @Test
    void documentsArchivedBeforeTheCheckExistedAreCaughtUpInBoundedBatches() {
        ApiClient anna = login(ANNA);
        byte[] photo = uniqueJpeg("older");
        archive.ocrText(photo, LETTER);
        String id = upload(anna, photo);
        awaitTextState(anna, id, "RECOGNIZED");

        // A document archived before this feature has no check yet.
        jdbc.sql("DELETE FROM document_text_checks WHERE document_id = ?").param(UUID.fromString(id)).update();
        assertThat(anna.json(anna.get("/api/documents/" + id)).path("textRecognition").path("state").asText())
                .isEqualTo("PENDING");

        assertThat(checks.findDue(Instant.now(), 1)).hasSizeLessThanOrEqualTo(1);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            textChecks.sweep();
            assertThat(anna.json(anna.get("/api/documents/" + id)).path("textRecognition").path("state").asText())
                    .isEqualTo("RECOGNIZED");
        });
        assertThat(checks.findDue(Instant.now(), 100)).doesNotContain(DocumentId.of(id));
    }

    @Test
    void checksStoredBeforeTheArchiveVersionWasRecordedAreReadAgainByTheSweep() {
        ApiClient anna = login(ANNA);
        byte[] scan = ("%PDF-1.4 scan before the archive version " + System.nanoTime())
                .getBytes(StandardCharsets.US_ASCII);
        archive.scannedPdf(scan, LETTER);
        ApiClient.Response uploaded = anna.upload("/api/documents", "scan.pdf", scan, List.of());
        assertThat(uploaded.status()).as(uploaded.body()).isEqualTo(201);
        String id = anna.json(uploaded).path("document").path("id").asText();
        awaitTextState(anna, id, "RECOGNIZED");
        assertThat(anna.json(anna.get("/api/documents/" + id)).path("archivePdfAvailable").asBoolean()).isTrue();

        // The state V2_7 leaves a check measured before the archive version existed in: count kept, due at once.
        jdbc.sql("UPDATE document_text_checks SET archive_version = NULL, next_attempt_at = checked_at"
                + " WHERE document_id = ?").param(UUID.fromString(id)).update();
        JsonNode before = anna.json(anna.get("/api/documents/" + id));
        assertThat(before.path("textRecognition").path("state").asText()).isEqualTo("RECOGNIZED");
        assertThat(before.path("archivePdfAvailable").asBoolean()).isFalse();
        assertThat(checks.findDue(Instant.now(), 1_000)).contains(DocumentId.of(id));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            textChecks.sweep();
            assertThat(anna.json(anna.get("/api/documents/" + id)).path("archivePdfAvailable").asBoolean())
                    .isTrue();
        });
        assertThat(checks.findDue(Instant.now(), 1_000)).doesNotContain(DocumentId.of(id));

        // The table refuses an archive version without the text it was read with.
        assertThatThrownBy(() -> jdbc.sql("UPDATE document_text_checks"
                + " SET recognized_characters = NULL WHERE document_id = ?").param(UUID.fromString(id)).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aFailedCheckNeverReplacesAMeasurementOfTheSameArchiveDocument() {
        ApiClient anna = login(ANNA);
        byte[] photo = uniqueJpeg("race");
        archive.ocrText(photo, "");
        String id = upload(anna, photo);
        awaitTextState(anna, id, "NONE");
        DocumentId documentId = DocumentId.of(id);
        ArchiveTextCheck measured = checks.find(documentId).orElseThrow();

        // A failure computed from an older read arrives after the measurement, as when two checks race.
        checks.save(ArchiveTextCheck.failed(documentId, measured.paperlessDocumentId(), Optional.empty(),
                Instant.now()));
        assertThat(checks.find(documentId)).contains(measured);

        // A check of another archive document replaces it: the old count no longer describes the archive.
        checks.save(ArchiveTextCheck.failed(documentId, measured.paperlessDocumentId() + 1_000, Optional.empty(),
                Instant.now()));
        assertThat(checks.find(documentId).orElseThrow().isMeasured()).isFalse();
        assertThat(anna.json(anna.get("/api/documents/" + id)).path("textRecognition").path("state").asText())
                .isEqualTo("PENDING");
        assertThat(checks.findDue(Instant.now(), 1_000)).contains(documentId);
        assertThat(textChecks.check(documentId)).isEqualTo(ArchiveTextService.Outcome.MEASURED);
        assertThat(checks.find(documentId).orElseThrow().paperlessDocumentId())
                .isEqualTo(measured.paperlessDocumentId());
    }

    private ApiClient login(String username) {
        ApiClient client = new ApiClient(port);
        assertThat(client.login(username, PASSWORD)).isTrue();
        return client;
    }

    private static String upload(ApiClient client, byte[] photo) {
        ApiClient.Response uploaded = client.upload("/api/documents", "IMG_" + System.nanoTime() + ".jpg", photo,
                List.of("HOUSE"));
        assertThat(uploaded.status()).as(uploaded.body()).isEqualTo(201);
        return client.json(uploaded).path("document").path("id").asText();
    }

    private static JsonNode awaitTextState(ApiClient client, String id, String state) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(client.json(client.get(
                "/api/documents/" + id)).path("textRecognition").path("state").asText()).isEqualTo(state));
        return client.json(client.get("/api/documents/" + id));
    }

    private static Optional<JsonNode> openNoTextCard(ApiClient client, String documentId) {
        JsonNode items = client.json(client.get("/api/reviews?status=OPEN"));
        return StreamSupport.stream(items.spliterator(), false)
                .filter(item -> "NO_TEXT_RECOGNIZED".equals(item.path("reason").asText()))
                .filter(item -> documentId.equals(item.path("sourceId").asText())).findFirst();
    }

    private static JsonNode item(JsonNode items, String id) {
        return StreamSupport.stream(items.spliterator(), false).filter(i -> id.equals(i.path("id").asText()))
                .findFirst().orElseThrow(() -> new AssertionError("document " + id + " not listed"));
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
