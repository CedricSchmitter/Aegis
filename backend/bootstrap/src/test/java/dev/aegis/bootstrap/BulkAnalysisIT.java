package dev.aegis.bootstrap;

import static dev.aegis.bootstrap.support.ControlledModelGateway.held;
import static dev.aegis.bootstrap.support.ControlledModelGateway.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.aegis.bootstrap.support.ApiClient;
import dev.aegis.bootstrap.support.ControlledModelGateway;
import dev.aegis.bootstrap.support.FakePaperlessArchive;
import dev.aegis.bootstrap.support.PostgresIT;
import dev.aegis.documents.application.AnalysisSettings;
import dev.aegis.documents.application.BulkAnalysisDriver;
import dev.aegis.identity.application.api.MemberCredentialsQuery;
import dev.aegis.review.application.api.ReviewInbox;
import dev.aegis.shared.Actor;
import dev.aegis.shared.MemberId;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * Bulk classification of existing documents (issue #115) against PostgreSQL, the real job scheduler, the REST API and
 * a controlled model: preview and explicit start, bounded batches, repeated job steps, cancellation of runs in
 * flight, a member edit that makes a result stale, one grouped review card, and who may start a run. Each test
 * scopes its run to a folder of its own, because the collection is shared by all integration tests.
 */
class BulkAnalysisIT extends PostgresIT {
    private static final String INVOICE = "11111111-0000-4000-8000-000000000001";
    private static final String INTERNET = "22222222-0000-4000-8000-000000000001";

    @LocalServerPort
    int port;
    @Autowired
    ControlledModelGateway model;
    @Autowired
    AnalysisSettings analysisSettings;
    @Autowired
    FakePaperlessArchive archive;
    @Autowired
    BulkAnalysisDriver driver;
    @Autowired
    MemberCredentialsQuery members;
    @Autowired
    ReviewInbox inbox;
    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void manualAnalysis() {
        model.reset();
        analysisSettings.setAutomatic(false);
    }

    /** Only one bulk run may run at a time; a test that fails half way must not block the next one. */
    @AfterEach
    void endRunningBulkRuns() {
        ApiClient lisa = loggedIn(ANNA);
        for (JsonNode bulk : lisa.json(lisa.get("/api/documents/bulk-analyses"))) {
            if ("RUNNING".equals(bulk.path("state").asText())) {
                lisa.postJson("/api/documents/bulk-analyses/" + bulk.path("id").asText() + "/cancel", "{}");
            }
        }
        model.reset();
    }

    private ApiClient loggedIn(String username) {
        ApiClient client = new ApiClient(port);
        assertThat(client.login(username, PASSWORD)).isTrue();
        return client;
    }

    /** Archived documents without analysis in a new folder; their OCR text names {@code marker}. */
    private List<String> existingDocuments(ApiClient client, String folderId, String marker, int count) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            byte[] pdf = ("%PDF-1.4\n% synthetic bulk " + marker + " " + UUID.randomUUID() + "\n")
                    .getBytes(StandardCharsets.US_ASCII);
            archive.ocrText(pdf, "Netzfunk " + marker + " Rechnung " + i + "\nIhre Rechnung für Internet und Telefon");
            ApiClient.Response upload = client.upload("/api/documents", "bestand-" + i + ".pdf", pdf, List.of());
            assertThat(upload.status()).as(upload.body()).isEqualTo(201);
            ids.add(client.json(upload).path("document").path("id").asText());
        }
        await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> assertThat(ids).allSatisfy(id ->
                assertThat(document(client, id).path("processing").path("state").asText()).isEqualTo("ARCHIVED")));
        StringBuilder items = new StringBuilder();
        for (String id : ids) {
            JsonNode document = document(client, id);
            assertThat(document.path("analysis").path("state").asText()).isEqualTo("NOT_REQUESTED");
            items.append(items.isEmpty() ? "" : ",").append("{\"documentId\":\"").append(id)
                    .append("\",\"expectedVersion\":").append(document.path("classification").path("version")
                            .asLong()).append('}');
        }
        ApiClient.Response placed = client.postJson("/api/documents/placement", "{\"categoryId\":\"" + folderId
                + "\",\"documents\":[" + items + "]}");
        assertThat(placed.status()).as(placed.body()).isEqualTo(200);
        return ids;
    }

    private String folder(ApiClient client, String name) {
        ApiClient.Response created = client.postJson("/api/categories", "{\"name\":\"" + name + "\"}");
        assertThat(created.status()).as(created.body()).isIn(200, 201);
        return client.json(created).path("id").asText();
    }

    private static String scope(String folderId) {
        return "{\"kind\":\"FOLDER\",\"folderId\":\"" + folderId + "\",\"includeSubfolders\":true}";
    }

    private JsonNode document(ApiClient client, String id) {
        return client.json(client.get("/api/documents/" + id));
    }

    private JsonNode bulk(ApiClient client, String id) {
        return client.json(client.get("/api/documents/bulk-analyses/" + id));
    }

    private JsonNode awaitBulk(ApiClient client, String id, String state) {
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(bulk(client, id).path("state").asText()).isEqualTo(state));
        return bulk(client, id);
    }

    private long runsFor(List<String> documentIds) {
        return documentIds.stream().mapToLong(id -> jdbc.sql("SELECT count(*) FROM ai_runs WHERE idempotency_key"
                + " LIKE ?").param("document-classification:" + id + ":%").query(Long.class).single()).sum();
    }

    private static Map<String, Object> answer(String marker, List<String> flags) {
        Map<String, Object> answer = new HashMap<>();
        answer.put("title", null);
        answer.put("documentDate", null);
        answer.put("documentDateEvidence", null);
        answer.put("conflictingDates", false);
        answer.put("documentType", claim(INVOICE, 0.95, "Ihre Rechnung"));
        answer.put("correspondent", null);
        answer.put("topics", List.of(claim(INTERNET, 0.9, "Internet und Telefon")));
        answer.put("shortTopic", "Internet");
        answer.put("subjects", List.of());
        answer.put("flags", flags);
        answer.put("placement", Map.of("newPath", List.of("Verträge " + marker), "confidence", 0.9,
                "evidence", "Ihre Rechnung"));
        return answer;
    }

    private static Map<String, Object> claim(String labelId, double confidence, String evidence) {
        Map<String, Object> claim = new HashMap<>();
        claim.put("labelId", labelId);
        claim.put("name", null);
        claim.put("confidence", confidence);
        claim.put("evidence", evidence);
        return claim;
    }

    @Test
    void aMemberPreviewsAndStartsABulkRunThatClassifiesInBoundedBatchesWithOneGroupedCard() throws Exception {
        ApiClient lisa = loggedIn(ANNA);
        MemberId anna = members.findCredentials(ANNA).orElseThrow().id();
        String marker = "Bulk" + UUID.randomUUID().toString().substring(0, 6);
        String folderId = folder(lisa, "Bestand " + marker);
        List<String> ids = existingDocuments(lisa, folderId, marker, 5);

        JsonNode preview = lisa.json(lisa.postJson("/api/documents/bulk-analyses/preview", scope(folderId)));
        assertThat(preview.path("documents").asInt()).isEqualTo(5);
        assertThat(preview.path("refusal").isNull()).isTrue();
        assertThat(preview.path("sample").findValuesAsString("id")).containsExactlyInAnyOrderElementsOf(ids);
        assertThat(preview.path("cost").path("currency").asText()).isEqualTo("USD");
        assertThat(preview.path("cost").path("days").asInt()).isEqualTo(1);
        assertThat(model.requests).as("a preview runs nothing").isEmpty();

        ApiClient.Response changed = lisa.postJson("/api/documents/bulk-analyses", "{\"scope\":" + scope(folderId)
                + ",\"expectedDocuments\":4}");
        assertThat(changed.status()).isEqualTo(400);
        assertThat(lisa.json(changed).path("code").asText()).isEqualTo("analysis.bulk.changed");

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // The first answer is doubtful, so its values stay suggestions for a member.
        model.answer(held(entered, release, json(answer(marker, List.of("POOR_OCR")))));
        model.otherwise(request -> json(answer(marker, List.of())));
        ApiClient.Response started = lisa.postJson("/api/documents/bulk-analyses", "{\"scope\":" + scope(folderId)
                + ",\"expectedDocuments\":5}");
        assertThat(started.status()).as(started.body()).isEqualTo(200);
        String bulkId = lisa.json(started).path("id").asText();
        assertThat(lisa.json(started).path("state").asText()).isEqualTo("RUNNING");

        // While the first call is held, the run keeps exactly its batch of two in flight and requests nothing more.
        assertThat(entered.await(40, TimeUnit.SECONDS)).isTrue();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(bulk(lisa, bulkId).path("inFlight")
                .asInt()).isEqualTo(2));
        Thread.sleep(1_000);
        JsonNode during = bulk(lisa, bulkId);
        assertThat(during.path("inFlight").asInt()).isEqualTo(2);
        assertThat(during.path("pending").asInt() + during.path("classified").asInt()).isEqualTo(3);
        assertThat(runsFor(ids)).isLessThanOrEqualTo(3);
        ApiClient.Response second = lisa.postJson("/api/documents/bulk-analyses", "{\"scope\":" + scope(folderId)
                + ",\"expectedDocuments\":0}");
        assertThat(lisa.json(second).path("code").asText()).isEqualTo("analysis.bulk.active");
        release.countDown();

        JsonNode done = awaitBulk(lisa, bulkId, "COMPLETED");
        assertThat(done.path("classified").asInt()).isEqualTo(5);
        assertThat(done.path("failed").asInt()).isZero();
        assertThat(done.path("needsReview").asInt()).isEqualTo(1);
        assertThat(done.path("reviewDocuments")).hasSize(1);
        // Every answer proposed a new folder, but a member filed these documents: their placement stands and no
        // proposal is offered (ADR-0005), exactly as for a classification on upload.
        assertThat(done.path("placementGroups")).isEmpty();
        for (String id : ids) {
            JsonNode classification = document(lisa, id).path("classification");
            assertThat(classification.path("categoryId").asText()).isEqualTo(folderId);
            assertThat(classification.path("placementSuggestion").isNull()).isTrue();
        }
        assertThat(runsFor(ids)).as("one run per document").isEqualTo(5);
        assertThat(model.requests).hasSize(5);
        for (String id : ids) {
            JsonNode analysis = document(lisa, id).path("analysis");
            assertThat(analysis.path("trigger").asText()).isEqualTo("BULK");
            assertThat(analysis.path("generation").asInt()).isEqualTo(1);
            assertThat(analysis.path("requestedBy").asText()).isEqualTo(anna.toString());
        }
        assertThat(jdbc.sql("SELECT count(*) FROM scheduled_tasks WHERE task_name = 'document-bulk-analysis'"
                + " AND task_instance = ?").param(bulkId).query(Long.class).single()).as("the job ended").isZero();

        // One card for the run instead of five; it names the counts and leads to the run.
        List<ReviewInbox.Item> cards = inbox.list(new Actor(anna), "OPEN", null);
        assertThat(cards).noneMatch(card -> ids.contains(card.sourceId()));
        assertThat(cards).filteredOn(card -> card.sourceId().equals(bulkId)).singleElement().satisfies(card -> {
            assertThat(card.sourceKind()).isEqualTo("BULK_ANALYSIS");
            assertThat(card.reason()).isEqualTo("BULK_CLASSIFICATION");
            assertThat(card.linkType()).isEqualTo("BULK_ANALYSIS");
            assertThat(card.params()).containsEntry("needsReview", "1").containsEntry("total", "5")
                    .containsEntry("classified", "5").containsEntry("failed", "0");
        });
    }

    @Test
    void cancellingStopsTheRunAndRepeatedStepsNeverClassifyADocumentTwice() throws Exception {
        ApiClient lisa = loggedIn(ANNA);
        ApiClient ben = loggedIn(BEN);
        String marker = "Cancel" + UUID.randomUUID().toString().substring(0, 6);
        String folderId = folder(lisa, "Bestand " + marker);
        List<String> ids = existingDocuments(lisa, folderId, marker, 4);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        model.answer(held(entered, release, json(answer(marker, List.of()))));
        model.otherwise(request -> json(answer(marker, List.of())));

        String bulkId = lisa.json(lisa.postJson("/api/documents/bulk-analyses", "{\"scope\":" + scope(folderId)
                + ",\"expectedDocuments\":4}")).path("id").asText();
        assertThat(entered.await(40, TimeUnit.SECONDS)).isTrue();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(bulk(lisa, bulkId).path("inFlight")
                .asInt()).isEqualTo(2));
        // A revived job or a restart repeats the step while the job itself keeps running: nothing twice.
        for (int i = 0; i < 3; i++) {
            driver.advance(UUID.fromString(bulkId));
        }
        assertThat(runsFor(ids)).isEqualTo(2);

        ApiClient.Response cancelled = ben.postJson("/api/documents/bulk-analyses/" + bulkId + "/cancel", "{}");
        assertThat(cancelled.status()).as(cancelled.body()).isEqualTo(200);
        JsonNode view = ben.json(cancelled);
        assertThat(view.path("state").asText()).isEqualTo("CANCELLED");
        assertThat(view.path("cancelled").asInt()).isEqualTo(4);
        release.countDown();

        // The held answer arrives after the cancellation and is never applied; the documents are as before.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(ids).allSatisfy(id ->
                assertThat(jdbc.sql("""
                        SELECT count(*) FROM ai_runs WHERE idempotency_key LIKE ?
                           AND state IN ('QUEUED', 'RUNNING', 'WAITING_RETRY')""")
                        .param("document-classification:" + id + ":%").query(Long.class).single()).isZero()));
        assertThat(driver.advance(UUID.fromString(bulkId))).isEqualTo(new BulkAnalysisDriver.Done());
        Thread.sleep(1_000);
        assertThat(runsFor(ids)).isEqualTo(2);
        assertThat(model.requests).hasSizeLessThanOrEqualTo(2);
        for (String id : ids) {
            JsonNode document = document(lisa, id);
            assertThat(document.path("analysis").path("state").asText()).isEqualTo("NOT_REQUESTED");
            assertThat(document.path("classification").path("labels")).isEmpty();
            assertThat(document.path("classification").path("placementSuggestion").isNull()).isTrue();
        }
        JsonNode preview = lisa.json(lisa.postJson("/api/documents/bulk-analyses/preview", scope(folderId)));
        assertThat(preview.path("documents").asInt()).as("the documents can be started again").isEqualTo(4);
    }

    @Test
    void aMemberEditDuringABulkRunWinsAndTheStaleResultIsSummarizedAsAFailure() throws Exception {
        ApiClient lisa = loggedIn(ANNA);
        ApiClient ben = loggedIn(BEN);
        String marker = "Stale" + UUID.randomUUID().toString().substring(0, 6);
        String folderId = folder(lisa, "Bestand " + marker);
        String id = existingDocuments(lisa, folderId, marker, 1).getFirst();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        model.answer(held(entered, release, json(answer(marker, List.of()))));

        String bulkId = lisa.json(lisa.postJson("/api/documents/bulk-analyses", "{\"scope\":" + scope(folderId)
                + ",\"expectedDocuments\":1}")).path("id").asText();
        assertThat(entered.await(40, TimeUnit.SECONDS)).isTrue();
        JsonNode during = document(ben, id);
        ApiClient.Response edit = ben.putJson("/api/documents/" + id + "/labels", "{\"labelIds\":[\"" + INTERNET
                + "\"],\"expectedVersion\":" + during.path("classification").path("version").asLong() + "}");
        assertThat(edit.status()).as(edit.body()).isEqualTo(200);
        release.countDown();

        JsonNode done = awaitBulk(lisa, bulkId, "COMPLETED");
        assertThat(done.path("failed").asInt()).isEqualTo(1);
        assertThat(done.path("failures")).singleElement().satisfies(group -> {
            assertThat(group.path("documents").asInt()).isEqualTo(1);
            assertThat(group.path("examples").get(0).path("id").asText()).isEqualTo(id);
        });
        JsonNode document = document(lisa, id);
        assertThat(document.path("analysis").path("failureCategory").asText()).isEqualTo("STALE_SOURCE");
        assertThat(document.path("classification").path("labels").findValuesAsString("labelId"))
                .containsExactly(INTERNET);
        assertThat(document.path("classification").path("labels").get(0).path("origin").asText())
                .isEqualTo("MANUAL");
    }

    @Test
    void onlyAMemberWhoMayUseAiStartsABulkRunAndAnonymousCallersAreRefused() {
        MemberId anna = members.findCredentials(ANNA).orElseThrow().id();
        ApiClient lisa = loggedIn(ANNA);
        String marker = "Auth" + UUID.randomUUID().toString().substring(0, 6);
        String folderId = folder(lisa, "Bestand " + marker);
        existingDocuments(lisa, folderId, marker, 1);
        model.notAllowed.add(anna);

        JsonNode preview = lisa.json(lisa.postJson("/api/documents/bulk-analyses/preview", scope(folderId)));
        assertThat(preview.path("refusal").asText()).isEqualTo("ai.provider.memberNotAllowed");
        ApiClient.Response refused = lisa.postJson("/api/documents/bulk-analyses", "{\"scope\":" + scope(folderId)
                + ",\"expectedDocuments\":1}");
        assertThat(refused.status()).isEqualTo(400);
        assertThat(lisa.json(refused).path("code").asText()).isEqualTo("ai.provider.memberNotAllowed");

        ApiClient anonymous = new ApiClient(port);
        assertThat(anonymous.get("/api/documents/bulk-analyses").status()).isEqualTo(401);
        assertThat(anonymous.postWithoutCsrf("/api/documents/bulk-analyses/preview", scope(folderId)).status())
                .isIn(401, 403);
        assertThat(lisa.json(lisa.get("/api/documents/bulk-analyses")).findValuesAsString("state"))
                .doesNotContain("RUNNING");
        assertThat(model.requests).isEmpty();
    }
}
