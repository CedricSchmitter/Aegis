package dev.aegis.bootstrap;

import static dev.aegis.bootstrap.support.ControlledModelGateway.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.aegis.bootstrap.support.ApiClient;
import dev.aegis.bootstrap.support.ControlledModelGateway;
import dev.aegis.bootstrap.support.FakePaperlessArchive;
import dev.aegis.bootstrap.support.PostgresIT;
import dev.aegis.documents.application.AnalysisSettings;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * Folder placement against PostgreSQL, the real job scheduler, the REST API and a controlled model (issue #67):
 * the automatic move into a matching folder with its provenance and revert, and accepting a proposed path, which
 * creates the missing folders and moves the document in one transaction - either both or neither, also when two
 * members accept the same path at the same time.
 */
class DocumentPlacementIT extends PostgresIT {
    private static final String INVOICE = "11111111-0000-4000-8000-000000000001";
    private static final String INSURANCE = "22222222-0000-4000-8000-000000000008";

    @LocalServerPort
    int port;
    @Autowired
    ControlledModelGateway model;
    @Autowired
    AnalysisSettings analysisSettings;
    @Autowired
    FakePaperlessArchive archive;
    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void automaticAnalysis() {
        model.reset();
        analysisSettings.setAutomatic(true);
    }

    @AfterEach
    void manualAgain() {
        analysisSettings.setAutomatic(false);
        model.reset();
    }

    // Helpers ---------------------------------------------------------------------------------------------------

    private ApiClient loggedIn(String username) {
        ApiClient client = new ApiClient(port);
        assertThat(client.login(username, PASSWORD)).isTrue();
        return client;
    }

    private record Policy(String text, byte[] pdf) {
    }

    private static Policy policy(String marker) {
        String text = "Muster Versicherung AG " + marker + "\nHausratversicherung - Versicherungsschein vom "
                + "02.03.2026\nJahresbeitrag 148,00 EUR";
        byte[] pdf = ("%PDF-1.4\n% synthetic policy " + marker + " " + UUID.randomUUID() + "\n")
                .getBytes(StandardCharsets.US_ASCII);
        return new Policy(text, pdf);
    }

    private String folder(ApiClient client, String parentId, String name) {
        ApiClient.Response created = client.postJson("/api/categories", "{\"parentId\":"
                + (parentId == null ? "null" : "\"" + parentId + "\"") + ",\"name\":\"" + name + "\"}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return client.json(created).path("id").asText();
    }

    private void implies(ApiClient client, String folderId, String labelId) {
        JsonNode folder = folder(client, folderId);
        ApiClient.Response changed = client.putJson("/api/categories/" + folderId + "/implications",
                "{\"labelIds\":[\"" + labelId + "\"],\"subjects\":[],\"expectedVersion\":"
                        + folder.path("version").asLong() + "}");
        assertThat(changed.status()).as(changed.body()).isEqualTo(200);
    }

    private JsonNode folder(ApiClient client, String folderId) {
        for (JsonNode category : client.json(client.get("/api/categories")).path("categories")) {
            if (category.path("id").asText().equals(folderId)) {
                return category;
            }
        }
        throw new AssertionError("no folder " + folderId);
    }

    /** The model's answer: the labels of an insurance policy plus the placement under test. */
    private static Map<String, Object> answer(Map<String, Object> placement) {
        Map<String, Object> answer = new HashMap<>();
        answer.put("title", "Hausratversicherung Versicherungsschein");
        answer.put("documentDate", "2026-03-02");
        answer.put("documentDateEvidence", "Versicherungsschein vom 02.03.2026");
        answer.put("conflictingDates", false);
        answer.put("documentType", claim(INVOICE, null, 0.9, "Jahresbeitrag"));
        answer.put("correspondent", null);
        answer.put("topics", List.of(claim(INSURANCE, null, 0.95, "Hausratversicherung")));
        answer.put("shortTopic", "Hausrat");
        answer.put("subjects", List.of());
        answer.put("placement", placement);
        answer.put("flags", List.of());
        return answer;
    }

    private static Map<String, Object> claim(String labelId, String name, double confidence, String evidence) {
        Map<String, Object> claim = new HashMap<>();
        claim.put("labelId", labelId);
        claim.put("name", name);
        claim.put("confidence", confidence);
        claim.put("evidence", evidence);
        return claim;
    }

    private static Map<String, Object> placement(String folderId, String anchorId, List<String> newPath) {
        Map<String, Object> placement = new HashMap<>();
        placement.put("folderId", folderId);
        placement.put("anchorFolderId", anchorId);
        placement.put("newPath", newPath);
        placement.put("confidence", 0.93);
        placement.put("evidence", "Hausratversicherung");
        return placement;
    }

    private String upload(ApiClient client, Policy policy, String filename) {
        archive.ocrText(policy.pdf(), policy.text());
        ApiClient.Response upload = client.upload("/api/documents", filename, policy.pdf(), List.of());
        assertThat(upload.status()).as(upload.body()).isEqualTo(201);
        return client.json(upload).path("document").path("id").asText();
    }

    private static final List<String> FINISHED = List.of("CLASSIFIED", "NEEDS_REVIEW", "FAILED", "UNAVAILABLE");

    /** Waits for the analysis to finish and reports why it finished differently than expected. */
    private JsonNode awaitAnalysis(ApiClient client, String id, String state) {
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(client.json(
                client.get("/api/documents/" + id)).path("analysis").path("state").asText()).isIn(FINISHED));
        JsonNode document = client.json(client.get("/api/documents/" + id));
        JsonNode analysis = document.path("analysis");
        assertThat(analysis.path("state").asText())
                .as("analysis of %s: %s / %s", id, analysis.path("failureCategory").asText(),
                        analysis.path("failureCode").asText())
                .isEqualTo(state);
        return document;
    }

    private long folderCount(String name) {
        return jdbc.sql("SELECT count(*) FROM categories WHERE name = ?").param(name).query(Long.class).single();
    }

    // Tests -----------------------------------------------------------------------------------------------------

    @Test
    void aMatchingFolderTakesTheDocumentAutomaticallyAndTheMoveCanBeReverted() {
        ApiClient lisa = loggedIn(ANNA);
        ApiClient cedric = loggedIn(BEN);
        String marker = UUID.randomUUID().toString().substring(0, 8);
        String haus = folder(lisa, null, "Haus " + marker);
        String versicherungen = folder(lisa, haus, "Versicherungen " + marker);
        implies(lisa, versicherungen, INSURANCE);
        model.otherwise(request -> json(answer(placement(versicherungen, null, List.of()))));

        String id = upload(lisa, policy(marker), "police.pdf");
        JsonNode filed = awaitAnalysis(cedric, id, "CLASSIFIED");

        JsonNode classification = filed.path("classification");
        assertThat(classification.path("categoryId").asText()).isEqualTo(versicherungen);
        assertThat(classification.path("placement").path("origin").asText()).isEqualTo("AUTOMATIC");
        assertThat(classification.path("placement").path("confidence").asDouble()).isEqualTo(0.93);
        assertThat(classification.path("placement").path("sourceRef").asText()).startsWith("ai-run:");
        assertThat(classification.path("placement").path("revertible").asBoolean()).isTrue();
        assertThat(classification.path("placementSuggestion").isNull()).isTrue();

        ApiClient.Response reverted = cedric.postJson("/api/documents/" + id + "/classification/placement/revert",
                "{\"expectedVersion\":" + classification.path("version").asLong() + "}");
        assertThat(reverted.status()).as(reverted.body()).isEqualTo(200);
        JsonNode after = cedric.json(reverted).path("classification");
        assertThat(after.path("categoryId").isNull()).isTrue();
        assertThat(after.path("placement").path("byMember").asBoolean()).isTrue();
    }

    @Test
    void acceptingAProposedPathCreatesTheFoldersAndMovesTheDocumentInOneTransaction() {
        ApiClient lisa = loggedIn(ANNA);
        String marker = UUID.randomUUID().toString().substring(0, 8);
        String haus = folder(lisa, null, "Haus " + marker);
        String hausrat = "Hausrat " + marker;
        model.otherwise(request -> json(answer(placement(null, haus, List.of("Versicherungen " + marker, hausrat)))));

        String id = upload(lisa, policy(marker), "police.pdf");
        JsonNode reviewed = awaitAnalysis(lisa, id, "NEEDS_REVIEW");
        JsonNode suggestion = reviewed.path("classification").path("placementSuggestion");

        assertThat(reviewed.path("classification").path("categoryId").isNull()).isTrue();
        assertThat(suggestion.path("newFolders")).hasSize(2);
        assertThat(folderCount(hausrat)).isZero();

        // A stale document version fails the whole acceptance: no folder is created either.
        ApiClient.Response stale = lisa.postJson("/api/documents/" + id + "/classification/placement/accept",
                acceptBody(haus, List.of("Versicherungen " + marker, hausrat),
                        reviewed.path("classification").path("version").asLong() + 5, null, false));
        assertThat(stale.status()).isEqualTo(409);
        assertThat(folderCount(hausrat)).as("nothing is created when the move cannot happen").isZero();

        ApiClient.Response accepted = lisa.postJson("/api/documents/" + id + "/classification/placement/accept",
                acceptBody(haus, List.of("Versicherungen " + marker, hausrat),
                        reviewed.path("classification").path("version").asLong(),
                        suggestion.path("anchorVersion").asLong(), false));
        assertThat(accepted.status()).as(accepted.body()).isEqualTo(200);
        JsonNode result = lisa.json(accepted);

        assertThat(result.path("createdFolders").asInt()).isEqualTo(2);
        assertThat(result.path("movedDocuments").asInt()).isEqualTo(1);
        assertThat(folderCount(hausrat)).isEqualTo(1);
        JsonNode document = result.path("document").path("classification");
        assertThat(document.path("categoryId").asText()).isEqualTo(result.path("folderId").asText());
        assertThat(document.path("placement").path("origin").asText()).isEqualTo("MANUAL");
        assertThat(document.path("placementSuggestion").isNull()).isTrue();
    }

    @Test
    void twoMembersAcceptingTheSamePathAtTheSameTimeCreateItOnlyOnce() throws Exception {
        ApiClient lisa = loggedIn(ANNA);
        ApiClient cedric = loggedIn(BEN);
        String marker = UUID.randomUUID().toString().substring(0, 8);
        String haus = folder(lisa, null, "Haus " + marker);
        String hausrat = "Hausrat " + marker;
        model.otherwise(request -> json(answer(placement(null, haus, List.of(hausrat)))));

        String first = upload(lisa, policy(marker + "a"), "police-a.pdf");
        String second = upload(lisa, policy(marker + "b"), "police-b.pdf");
        JsonNode firstReviewed = awaitAnalysis(lisa, first, "NEEDS_REVIEW");
        JsonNode secondReviewed = awaitAnalysis(lisa, second, "NEEDS_REVIEW");
        long anchorVersion = firstReviewed.path("classification").path("placementSuggestion").path("anchorVersion")
                .asLong();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> accepts = List.of(
                    accept(lisa, first, haus, hausrat,
                            firstReviewed.path("classification").path("version").asLong(), anchorVersion),
                    accept(cedric, second, haus, hausrat,
                            secondReviewed.path("classification").path("version").asLong(), anchorVersion));
            List<Future<Integer>> results = pool.invokeAll(accepts);
            assertThat(List.of(results.get(0).get(), results.get(1).get())).containsOnly(200);
        } finally {
            pool.shutdownNow();
        }

        assertThat(folderCount(hausrat)).as("the second acceptance reuses the folder").isEqualTo(1);
        String folderId = jdbc.sql("SELECT id FROM categories WHERE name = ?").param(hausrat)
                .query(UUID.class).single().toString();
        for (String id : List.of(first, second)) {
            assertThat(lisa.json(lisa.get("/api/documents/" + id)).path("classification").path("categoryId")
                    .asText()).isEqualTo(folderId);
        }
    }

    private Callable<Integer> accept(ApiClient client, String documentId, String anchor, String name,
            long expectedVersion, long anchorVersion) {
        return () -> client.postJson("/api/documents/" + documentId + "/classification/placement/accept",
                acceptBody(anchor, List.of(name), expectedVersion, anchorVersion, false)).status();
    }

    private static String acceptBody(String anchorId, List<String> newPath, long expectedVersion,
            Long anchorVersion, boolean alsoForOthers) {
        String path = String.join(",", newPath.stream().map(name -> "\"" + name + "\"").toList());
        return "{\"folderId\":null,\"anchorFolderId\":\"" + anchorId + "\",\"newPath\":[" + path
                + "],\"expectedVersion\":" + expectedVersion + ",\"expectedAnchorVersion\":"
                + (anchorVersion == null ? "null" : anchorVersion) + ",\"alsoForOtherDocuments\":"
                + alsoForOthers + "}";
    }
}
