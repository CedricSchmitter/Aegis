package dev.aegis.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.aegis.bootstrap.support.ApiClient;
import dev.aegis.bootstrap.support.FakePaperlessArchive;
import dev.aegis.bootstrap.support.PostgresIT;
import dev.aegis.planning.domain.TaskRepository;
import dev.aegis.planning.domain.TaskStatus;
import dev.aegis.shared.ContractId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * Permanent deletion from the trash (ADR-0021) against PostgreSQL, the scheduler, the HTTP API and the
 * in-process Paperless stand-in: only the trash is purged, the archive original goes with the record, links that cited
 * it say so, notices and cards about it disappear, and an uncertain archive outcome keeps the purge pending.
 */
class PurgeIT extends PostgresIT {
    @Autowired FakePaperlessArchive archive;
    @Autowired TaskRepository tasks;
    @Autowired JdbcClient jdbc;
    @Autowired Clock clock;
    @LocalServerPort int port;

    @Test
    void anArchivedDocumentIsDeletedEverywhereAndWhatCitedItSaysSo() throws Exception {
        ApiClient anna = login(ANNA);
        ApiClient ben = login(BEN);
        byte[] pdf = uniquePdf("purge-archived");
        String documentId = upload(anna, "Falsch hochgeladen.pdf", pdf);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(document(anna, documentId)
                .path("processing").path("state").asText()).isEqualTo("ARCHIVED"));
        int archiveId = document(anna, documentId).path("processing").path("paperlessDocumentId").asInt();
        String stagingPath = jdbc.sql("SELECT staging_path FROM documents WHERE id = ?")
                .param(UUID.fromString(documentId)).query(String.class).single();
        var task = anna.postJson("/api/tasks", "{\"title\":\"Scan prüfen\",\"sourceDocumentId\":\"" + documentId
                + "\",\"dueDate\":\"" + LocalDate.now(clock) + "\"}");
        String taskId = anna.json(task).path("id").asText();
        String contractId = contractCitingPage(anna, documentId);

        // Outside the trash nothing can be deleted permanently.
        var refused = anna.postJson("/api/documents/" + documentId + "/purge", version(document(anna, documentId)));
        assertThat(refused.status()).isEqualTo(409);
        assertThat(anna.json(refused).path("code").asText()).isEqualTo("document.purge.notTrashed");

        JsonNode trashed = anna.json(anna.postJson("/api/documents/" + documentId + "/trash",
                version(document(anna, documentId))));
        var purge = ben.postJson("/api/documents/" + documentId + "/purge", version(trashed));
        assertThat(purge.status()).as(purge.body()).isEqualTo(202);
        assertThat(ben.json(purge).path("trash").path("purge").path("state").asText()).isEqualTo("PENDING");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(anna.get("/api/documents/"
                + documentId).status()).isEqualTo(404));
        assertThat(archive.holds(archiveId)).isFalse();
        assertThat(archive.purged).contains(archiveId);
        assertThat(Files.exists(DATA.resolve("ingress").resolve(stagingPath))).isFalse();
        assertThat(anna.get("/api/documents/trash").body()).doesNotContain(documentId);
        UUID id = UUID.fromString(documentId);
        for (String table : List.of("document_subjects", "document_classifications", "document_analyses",
                "document_text_checks")) {
            assertThat(jdbc.sql("SELECT count(*) FROM " + table + " WHERE document_id = ?").param(id)
                    .query(Long.class).single()).as(table).isZero();
        }
        // The deletion is recorded with who and when, and nothing of the content.
        assertThat(jdbc.sql("SELECT requested_by FROM document_purges WHERE document_id = ?").param(id)
                .query(UUID.class).single()).isEqualTo(UUID.fromString(memberId(ben)));

        JsonNode source = anna.json(anna.get("/api/tasks/" + taskId)).path("source");
        assertThat(source.path("documentDeleted").asBoolean()).isTrue();
        assertThat(source.path("documentTitle").isNull()).isTrue();
        JsonNode fact = anna.json(anna.get("/api/contracts/" + contractId)).path("facts").get(0).path("source");
        assertThat(fact.path("documentDeleted").asBoolean()).isTrue();
        assertThat(fact.path("page").asInt()).isEqualTo(2);
        assertThat(fact.path("quote").asText()).isEqualTo("drei Monate zum Laufzeitende");

        // A repeated request after a lost response is harmless; the same bytes are a new upload now.
        assertThat(ben.postJson("/api/documents/" + documentId + "/purge", version(trashed)).status())
                .isEqualTo(204);
        var again = anna.upload("/api/documents", "noch einmal.pdf", pdf, List.of());
        assertThat(again.status()).as(again.body()).isEqualTo(201);
        assertThat(anna.json(again).path("document").path("id").asText()).isNotEqualTo(documentId);
    }

    @Test
    void aFailedUploadTakesItsNoticeAndReviewCardWithIt() {
        ApiClient anna = login(ANNA);
        String documentId;
        archive.available = false;
        try {
            documentId = upload(anna, "ausfall.pdf", ("%PDF-1.4\n% purge failure " + System.nanoTime() + "\n")
                    .getBytes(StandardCharsets.US_ASCII));
            String failed = documentId;
            await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(document(anna, failed)
                    .path("processing").path("state").asText()).isEqualTo("FAILED"));
        } finally {
            archive.available = true;
        }
        String cardId = cardFor(anna, documentId).path("id").asText();
        assertThat(noticeCount("DOCUMENT", documentId)).isEqualTo(1);
        assertThat(noticeCount("REVIEW", cardId)).isPositive();

        JsonNode trashed = anna.json(anna.postJson("/api/documents/" + documentId + "/trash",
                version(document(anna, documentId))));
        assertThat(anna.postJson("/api/documents/" + documentId + "/purge", version(trashed)).status())
                .isEqualTo(202);

        String purged = documentId;
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(anna.get("/api/documents/" + purged)
                .status()).isEqualTo(404));
        assertThat(noticeCount("DOCUMENT", documentId)).isZero();
        assertThat(noticeCount("REVIEW", cardId)).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM review_items WHERE source_id = ? OR link_id = ?")
                .params(documentId, documentId).query(Long.class).single()).isZero();
        assertThat(anna.get("/api/reviews").body()).doesNotContain(documentId);
    }

    @Test
    void anUnreachableArchiveKeepsThePurgePendingAndTheDocumentCannotBeRestoredMeanwhile() throws Exception {
        ApiClient anna = login(ANNA);
        String documentId = upload(anna, "später löschen.pdf", uniquePdf("purge-outage"));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(document(anna, documentId)
                .path("processing").path("state").asText()).isEqualTo("ARCHIVED"));
        int archiveId = document(anna, documentId).path("processing").path("paperlessDocumentId").asInt();
        JsonNode trashed = anna.json(anna.postJson("/api/documents/" + documentId + "/trash",
                version(document(anna, documentId))));

        archive.available = false;
        try {
            assertThat(anna.postJson("/api/documents/" + documentId + "/purge", version(trashed)).status())
                    .isEqualTo(202);
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(jdbc.sql(
                    "SELECT purge_attempts FROM documents WHERE id = ?").param(UUID.fromString(documentId))
                    .query(Integer.class).single()).isPositive());
            JsonNode pending = document(anna, documentId);
            assertThat(pending.path("trash").path("purge").path("state").asText()).isEqualTo("PENDING");
            var restore = anna.postJson("/api/documents/" + documentId + "/restore", version(pending));
            assertThat(restore.status()).isEqualTo(409);
            assertThat(anna.json(restore).path("code").asText()).isEqualTo("document.purge.pending");
            assertThat(archive.purged).doesNotContain(archiveId);
        } finally {
            archive.available = true;
        }

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(anna.get("/api/documents/"
                + documentId).status()).isEqualTo(404));
        assertThat(archive.purged).contains(archiveId);
    }

    @Test
    void aTrashedTaskIsDeletedOverHttpAndARepeatedRequestStillAnswers204() {
        ApiClient anna = login(ANNA);
        JsonNode task = anna.json(anna.postJson("/api/tasks", "{\"title\":\"Doppelt " + System.nanoTime()
                + "\",\"dueDate\":\"" + LocalDate.now(clock) + "\"}"));
        String taskId = task.path("id").asText();
        var refused = anna.postJson("/api/tasks/" + taskId + "/purge", version(task));
        assertThat(refused.status()).isEqualTo(409);
        assertThat(anna.json(refused).path("code").asText()).isEqualTo("task.purge.notTrashed");
        JsonNode trashed = anna.json(anna.postJson("/api/tasks/" + taskId + "/trash", version(task)));

        assertThat(anna.postJson("/api/tasks/" + taskId + "/purge", version(trashed)).status()).isEqualTo(204);

        assertThat(anna.get("/api/tasks/" + taskId).status()).isEqualTo(404);
        assertThat(anna.get("/api/tasks/trash").body()).doesNotContain(taskId);
        assertThat(anna.postJson("/api/tasks/" + taskId + "/purge", version(trashed)).status()).isEqualTo(204);
        assertThat(jdbc.sql("SELECT count(*) FROM task_purges WHERE task_id = ?").param(UUID.fromString(taskId))
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aDeletedContractKeepsTheCompletedReviewAsHistoryAndRemovesTheSupersededOne() {
        ApiClient anna = login(ANNA);
        LocalDate first = LocalDate.now(clock).plusDays(60);
        JsonNode contract = anna.json(anna.postJson("/api/contracts", "{\"name\":\"Löschstudio " + System.nanoTime()
                + "\"}"));
        String contractId = contract.path("id").asText();
        contract = enterDeadline(anna, contractId, contract, first);
        ContractId id = ContractId.of(contractId);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(tasks.findCancellationReviews(id))
                .hasSize(1));
        String doneId = tasks.findCancellationReviews(id).getFirst().id().toString();
        JsonNode done = anna.json(anna.get("/api/tasks/" + doneId));
        assertThat(anna.postJson("/api/tasks/" + doneId + "/complete", version(done)).status()).isEqualTo(200);
        // A corrected deadline is a new occurrence; the completed one stays completed.
        contract = anna.json(anna.get("/api/contracts/" + contractId));
        var corrected = anna.postJson("/api/contracts/" + contractId + "/facts/" + contract.path("facts").get(0)
                .path("id").asText() + "/correct", "{\"value\":\"" + first.plusDays(30) + "\",\"expectedVersion\":"
                + contract.path("version").asLong() + "}");
        assertThat(corrected.status()).as(corrected.body()).isEqualTo(200);
        contract = anna.json(corrected);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(tasks.findCancellationReviews(id))
                .hasSize(2).anyMatch(task -> task.status() == TaskStatus.OPEN));
        String supersededId = tasks.findCancellationReviews(id).stream()
                .filter(task -> task.status() == TaskStatus.OPEN).findFirst().orElseThrow().id().toString();

        JsonNode trashed = anna.json(anna.postJson("/api/contracts/" + contractId + "/trash", version(contract)));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(anna.json(anna.get("/api/tasks/"
                + supersededId)).path("status").asText()).isEqualTo("CANCELLED"));
        assertThat(anna.postJson("/api/contracts/" + contractId + "/purge", version(trashed)).status())
                .isEqualTo(204);

        assertThat(anna.get("/api/contracts/" + contractId).status()).isEqualTo(404);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(anna.get("/api/tasks/" + supersededId)
                .status()).isEqualTo(404));
        JsonNode history = anna.json(anna.get("/api/tasks/" + doneId));
        assertThat(history.path("status").asText()).isEqualTo("DONE");
        assertThat(history.path("cancellationReview").path("contractDeleted").asBoolean()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM contract_facts WHERE contract_id = ?")
                .param(UUID.fromString(contractId)).query(Long.class).single()).isZero();
        assertThat(anna.postJson("/api/contracts/" + contractId + "/purge", version(trashed)).status())
                .isEqualTo(204);
    }

    private JsonNode enterDeadline(ApiClient client, String contractId, JsonNode contract, LocalDate deadline) {
        var entered = client.postJson("/api/contracts/" + contractId + "/facts", "{\"type\":"
                + "\"CANCELLATION_DEADLINE\",\"value\":\"" + deadline + "\",\"expectedVersion\":"
                + contract.path("version").asLong() + "}");
        assertThat(entered.status()).as(entered.body()).isIn(200, 201);
        return client.json(entered);
    }

    private String contractCitingPage(ApiClient client, String documentId) {
        JsonNode contract = client.json(client.postJson("/api/contracts", "{\"name\":\"Strom " + System.nanoTime()
                + "\"}"));
        String contractId = contract.path("id").asText();
        var entered = client.postJson("/api/contracts/" + contractId + "/facts", "{\"type\":\"NOTICE_PERIOD\","
                + "\"value\":\"P3M\",\"sourceDocumentId\":\"" + documentId + "\",\"sourcePage\":2,"
                + "\"sourceQuote\":\"drei Monate zum Laufzeitende\",\"expectedVersion\":"
                + contract.path("version").asLong() + "}");
        assertThat(entered.status()).as(entered.body()).isIn(200, 201);
        return contractId;
    }

    private ApiClient login(String username) {
        ApiClient client = new ApiClient(port);
        assertThat(client.login(username, PASSWORD)).isTrue();
        return client;
    }

    private static String upload(ApiClient client, String filename, byte[] bytes) {
        ApiClient.Response uploaded = client.upload("/api/documents", filename, bytes, List.of("HOUSE"));
        assertThat(uploaded.status()).as(uploaded.body()).isEqualTo(201);
        return client.json(uploaded).path("document").path("id").asText();
    }

    private static JsonNode document(ApiClient client, String id) {
        return client.json(client.get("/api/documents/" + id));
    }

    private static String version(JsonNode record) {
        return "{\"expectedVersion\":" + record.path("version").asLong() + "}";
    }

    private static String memberId(ApiClient client) {
        return client.json(client.get("/api/auth/session")).path("member").path("id").asText();
    }

    private static JsonNode cardFor(ApiClient client, String documentId) {
        return StreamSupport.stream(client.json(client.get("/api/reviews?status=OPEN")).spliterator(), false)
                .filter(card -> card.path("sourceId").asText().equals(documentId)).findFirst().orElseThrow();
    }

    private long noticeCount(String linkType, String linkId) {
        return jdbc.sql("SELECT count(*) FROM notices WHERE link_type = ? AND link_id = ?").params(linkType, linkId)
                .query(Long.class).single();
    }

    private static byte[] uniquePdf(String label) throws Exception {
        byte[] fixture = fixtureBytes();
        byte[] marker = (" %% " + label + " " + System.nanoTime() + " %% ").getBytes(StandardCharsets.US_ASCII);
        byte[] pdf = new byte[fixture.length + marker.length];
        System.arraycopy(fixture, 0, pdf, 0, fixture.length);
        System.arraycopy(marker, 0, pdf, fixture.length, marker.length);
        return pdf;
    }
}
