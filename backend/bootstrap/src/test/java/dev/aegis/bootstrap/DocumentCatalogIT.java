package dev.aegis.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.aegis.bootstrap.support.ApiClient;
import dev.aegis.bootstrap.support.PostgresIT;
import dev.aegis.documents.application.api.ProposeClassification;
import dev.aegis.identity.application.api.MemberCredentialsQuery;
import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;

/**
 * Folder tree, label vocabulary and faceted search over the HTTP API against PostgreSQL: the owner example
 * (Rechnung, Telekom, Internet, Haus), shared visibility, indexed filtering with counts, folder moves and
 * deletions that keep documents and labels, and label merges.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DocumentCatalogIT extends PostgresIT {
    @LocalServerPort
    int port;

    @Autowired
    ProposeClassification proposals;

    @Autowired
    MemberCredentialsQuery credentials;

    private static String houseId;
    private static String invoicesId;
    private static String internetId;
    private static String telekomLabelId;
    private static String invoiceLabelId;
    private static String internetLabelId;
    private static String telekomDocumentId;
    private static String unsortedDocumentId;

    private ApiClient loggedIn(String username) {
        ApiClient client = new ApiClient(port);
        assertThat(client.login(username, PASSWORD)).as("login " + username).isTrue();
        return client;
    }

    private static byte[] pdf(String marker) {
        return ("%PDF-1.4\n% " + marker + " " + System.nanoTime() + "\n").getBytes(StandardCharsets.US_ASCII);
    }

    private String builtinLabel(ApiClient client, String key) {
        JsonNode labels = client.json(client.get("/api/labels"));
        for (JsonNode label : labels) {
            if (key.equals(label.path("builtinKey").asText(null))) {
                return label.path("id").asText();
            }
        }
        throw new IllegalStateException("built-in label " + key + " is missing");
    }

    private long version(ApiClient client, String categoryId) {
        for (JsonNode category : client.json(client.get("/api/categories")).path("categories")) {
            if (categoryId.equals(category.path("id").asText())) {
                return category.path("version").asLong();
            }
        }
        throw new IllegalStateException("folder " + categoryId + " is missing");
    }

    private JsonNode document(ApiClient client, String id) {
        return client.json(client.get("/api/documents/" + id));
    }

    @Test
    @Order(1)
    void builtInVocabularyIsSeededWithStableKeysAndBothLanguages() {
        ApiClient lisa = loggedIn(ANNA);

        JsonNode labels = lisa.json(lisa.get("/api/labels"));

        assertThat(labels).isNotEmpty();
        assertThat(labels).anySatisfy(label -> {
            assertThat(label.path("builtinKey").asText()).isEqualTo("invoice");
            assertThat(label.path("facet").asText()).isEqualTo("DOCUMENT_TYPE");
            assertThat(label.path("name").isNull()).as("built-in labels are translated by the UI").isTrue();
            assertThat(label.path("aliases")).isNotEmpty();
        });
        invoiceLabelId = builtinLabel(lisa, "invoice");
        internetLabelId = builtinLabel(lisa, "internet");
        assertThat(lisa.json(lisa.get("/api/labels/resolve?facet=DOCUMENT_TYPE&name=rechnung")).path("id").asText())
                .isEqualTo(invoiceLabelId);
        assertThat(lisa.json(lisa.get("/api/labels/resolve?facet=DOCUMENT_TYPE&name=INVOICE")).path("id").asText())
                .isEqualTo(invoiceLabelId);
        assertThat(lisa.get("/api/labels/resolve?facet=TOPIC&name=nothing-like-this").status()).isEqualTo(404);
    }

    @Test
    @Order(2)
    void foldersCarryTheLabelsTheirDocumentsInherit() {
        ApiClient lisa = loggedIn(ANNA);

        houseId = lisa.json(lisa.postJson("/api/categories", "{\"name\":\"Haus\"}")).path("id").asText();
        invoicesId = lisa.json(lisa.postJson("/api/categories",
                "{\"parentId\":\"" + houseId + "\",\"name\":\"Rechnungen\"}")).path("id").asText();
        internetId = lisa.json(lisa.postJson("/api/categories",
                "{\"parentId\":\"" + invoicesId + "\",\"name\":\"Internet\"}")).path("id").asText();

        ApiClient.Response duplicate = lisa.postJson("/api/categories", "{\"name\":\"haus\"}");
        assertThat(duplicate.status()).isEqualTo(400);
        assertThat(lisa.json(duplicate).path("code").asText()).isEqualTo("category.name.duplicate");

        lisa.putJson("/api/categories/" + houseId + "/implications", "{\"labelIds\":[],\"subjects\":"
                + "[{\"kind\":\"HOUSE\"}],\"expectedVersion\":" + version(lisa, houseId) + "}");
        lisa.putJson("/api/categories/" + invoicesId + "/implications", "{\"labelIds\":[\"" + invoiceLabelId
                + "\"],\"subjects\":[],\"expectedVersion\":" + version(lisa, invoicesId) + "}");
        lisa.putJson("/api/categories/" + internetId + "/implications", "{\"labelIds\":[\"" + internetLabelId
                + "\"],\"subjects\":[],\"expectedVersion\":" + version(lisa, internetId) + "}");

        JsonNode tree = lisa.json(lisa.get("/api/categories"));
        assertThat(tree.path("categories")).extracting(node -> node.path("name").asText())
                .containsSubsequence("Haus", "Rechnungen", "Internet");
    }

    @Test
    @Order(3)
    void theOwnerExampleShowsAllFourLabelsWithoutMaintainingHouseTwice() {
        ApiClient lisa = loggedIn(ANNA);
        telekomLabelId = lisa.json(lisa.postJson("/api/labels",
                "{\"facet\":\"ORGANIZATION\",\"name\":\"Telekom\"}")).path("id").asText();
        ApiClient.Response again = lisa.postJson("/api/labels",
                "{\"facet\":\"ORGANIZATION\",\"name\":\" telekom \"}");
        assertThat(again.status()).isEqualTo(400);
        assertThat(lisa.json(again).path("code").asText()).isEqualTo("label.exists");
        assertThat(lisa.json(again).path("existingId").asText()).isEqualTo(telekomLabelId);

        ApiClient.Response upload = lisa.upload("/api/documents?categoryId=" + internetId,
                "Telekom Rechnung 09-2026.pdf", pdf("telekom invoice"), List.of());
        assertThat(upload.status()).as(upload.body()).isEqualTo(201);
        telekomDocumentId = lisa.json(upload).path("document").path("id").asText();

        JsonNode document = document(lisa, telekomDocumentId);
        long classificationVersion = document.path("classification").path("version").asLong();
        JsonNode labelled = lisa.json(lisa.putJson("/api/documents/" + telekomDocumentId + "/labels",
                "{\"labelIds\":[\"" + telekomLabelId + "\"],\"expectedVersion\":" + classificationVersion + "}"));

        assertThat(labelled.path("classification").path("path")).extracting(node -> node.path("name").asText())
                .containsExactly("Haus", "Rechnungen", "Internet");
        assertThat(labelled.path("classification").path("labels")).hasSize(3)
                .anySatisfy(chip -> {
                    assertThat(chip.path("builtinKey").asText()).isEqualTo("invoice");
                    assertThat(chip.path("source").asText()).isEqualTo("FOLDER");
                    assertThat(chip.path("folderId").asText()).isEqualTo(invoicesId);
                })
                .anySatisfy(chip -> {
                    assertThat(chip.path("name").asText()).isEqualTo("Telekom");
                    assertThat(chip.path("source").asText()).isEqualTo("OWN");
                    assertThat(chip.path("origin").asText()).isEqualTo("MANUAL");
                    assertThat(chip.path("reviewState").asText()).isEqualTo("CONFIRMED");
                })
                .anySatisfy(chip -> assertThat(chip.path("builtinKey").asText()).isEqualTo("internet"));
        assertThat(labelled.path("classification").path("folderSubjects")).singleElement()
                .satisfies(subject -> assertThat(subject.path("kind").asText()).isEqualTo("HOUSE"));

        ApiClient.Response staleEdit = lisa.putJson("/api/documents/" + telekomDocumentId + "/labels",
                "{\"labelIds\":[],\"expectedVersion\":" + classificationVersion + "}");
        assertThat(staleEdit.status()).isEqualTo(409);
        assertThat(lisa.json(staleEdit).path("code").asText()).isEqualTo("version.conflict");
    }

    @Test
    @Order(4)
    void facetedSearchIsEvaluatedInTheDatabaseAndBothMembersSeeTheSameResults() {
        ApiClient lisa = loggedIn(ANNA);
        ApiClient cedric = loggedIn(BEN);
        ApiClient.Response unsorted = lisa.upload("/api/documents", "Unsortiertes Schreiben.pdf",
                pdf("unsorted letter"), List.of());
        unsortedDocumentId = lisa.json(unsorted).path("document").path("id").asText();

        for (ApiClient client : List.of(lisa, cedric)) {
            assertThat(ids(client, "/api/documents?organization=" + telekomLabelId))
                    .containsExactly(telekomDocumentId);
            assertThat(ids(client, "/api/documents?type=" + invoiceLabelId)).containsExactly(telekomDocumentId);
            assertThat(ids(client, "/api/documents?topic=" + internetLabelId)).containsExactly(telekomDocumentId);
            assertThat(ids(client, "/api/documents?subject=HOUSE")).contains(telekomDocumentId);
            assertThat(ids(client, "/api/documents?category=" + houseId)).containsExactly(telekomDocumentId);
            assertThat(ids(client, "/api/documents?category=" + houseId + "&subfolders=false")).isEmpty();
            assertThat(ids(client, "/api/documents?category=unsorted")).contains(unsortedDocumentId)
                    .doesNotContain(telekomDocumentId);
            assertThat(ids(client, "/api/documents?missing=DOCUMENT_TYPE")).contains(unsortedDocumentId)
                    .doesNotContain(telekomDocumentId);
            assertThat(ids(client, "/api/documents?organization=" + telekomLabelId + "&topic="
                    + internetLabelId)).containsExactly(telekomDocumentId);
        }

        JsonNode filtered = lisa.json(lisa.get("/api/documents?organization=" + telekomLabelId));
        assertThat(filtered.path("matchingDocuments").asLong()).isEqualTo(1);
        assertThat(filtered.path("totalDocuments").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(filtered.path("facets").path("values").path("TOPIC").path(internetLabelId).asLong())
                .isEqualTo(1);
        assertThat(filtered.path("facets").path("values").path("ORGANIZATION").path(telekomLabelId).asLong())
                .as("counts of the filtered group ignore its own filter").isEqualTo(1);
        assertThat(filtered.path("facets").path("missing").path("TAG").asLong()).isEqualTo(1);

        JsonNode combined = lisa.json(lisa.get("/api/documents?query=telekom&type=" + invoiceLabelId));
        assertThat(combined.path("items")).hasSize(1);
        assertThat(combined.path("contentSearch").asText()).isEqualTo("READY");

        JsonNode paged = lisa.json(lisa.get("/api/documents?size=1&page=0&sort=TITLE"));
        assertThat(paged.path("items")).hasSize(1);
        assertThat(paged.path("size").asInt()).isEqualTo(1);
        assertThat(paged.path("matchingDocuments").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(lisa.get("/api/documents?sort=RELEVANCE").status()).isEqualTo(400);
    }

    @Test
    @Order(5)
    void movingADocumentUpdatesInheritedLabelsAndKeepsOwnOnes() {
        ApiClient lisa = loggedIn(ANNA);
        String otherId = lisa.json(lisa.postJson("/api/categories", "{\"name\":\"Auto\"}")).path("id").asText();
        long classificationVersion = document(lisa, telekomDocumentId).path("classification").path("version")
                .asLong();

        JsonNode moved = lisa.json(lisa.putJson("/api/documents/" + telekomDocumentId + "/category",
                "{\"categoryId\":\"" + otherId + "\",\"expectedVersion\":" + classificationVersion + "}"));

        assertThat(moved.path("classification").path("labels")).singleElement()
                .satisfies(chip -> assertThat(chip.path("name").asText()).isEqualTo("Telekom"));
        assertThat(moved.path("classification").path("folderSubjects")).isEmpty();

        JsonNode back = lisa.json(lisa.putJson("/api/documents/" + telekomDocumentId + "/category",
                "{\"categoryId\":\"" + internetId + "\",\"expectedVersion\":"
                        + moved.path("classification").path("version").asLong() + "}"));
        assertThat(back.path("classification").path("labels")).hasSize(3);
        lisa.json(lisa.delete("/api/categories/" + otherId + "?expectedVersion=" + version(lisa, otherId)));
    }

    @Test
    @Order(6)
    void deletingAFolderMovesDocumentsUpAndKeepsTheLabelsTheyWouldLose() {
        ApiClient lisa = loggedIn(ANNA);

        JsonNode result = lisa.json(lisa.delete("/api/categories/" + invoicesId + "?expectedVersion="
                + version(lisa, invoicesId)));

        assertThat(result.path("contentsMovedTo").asText()).isEqualTo(houseId);
        assertThat(result.path("foldersMoved").asInt()).isEqualTo(1);
        assertThat(result.path("labelsKept").asInt()).isEqualTo(1);
        JsonNode document = document(lisa, telekomDocumentId);
        assertThat(document.path("classification").path("path")).extracting(node -> node.path("name").asText())
                .containsExactly("Haus", "Internet");
        assertThat(document.path("classification").path("labels")).hasSize(3).anySatisfy(chip -> {
            assertThat(chip.path("builtinKey").asText()).isEqualTo("invoice");
            assertThat(chip.path("source").asText()).as("the folder type became an own label").isEqualTo("OWN");
        });
        assertThat(ids(lisa, "/api/documents?type=" + invoiceLabelId)).contains(telekomDocumentId);
        assertThat(lisa.get("/api/documents/" + telekomDocumentId + "/original").status()).isEqualTo(200);
    }

    @Test
    @Order(7)
    void mergingLabelsRewritesAssignmentsAndKeepsTheDocument() {
        ApiClient lisa = loggedIn(ANNA);
        String longNameId = lisa.json(lisa.postJson("/api/labels",
                "{\"facet\":\"ORGANIZATION\",\"name\":\"Telekom Deutschland GmbH\"}")).path("id").asText();
        long classificationVersion = document(lisa, telekomDocumentId).path("classification").path("version")
                .asLong();
        lisa.putJson("/api/documents/" + telekomDocumentId + "/labels", "{\"labelIds\":[\"" + longNameId
                + "\",\"" + invoiceLabelId + "\"],\"expectedVersion\":" + classificationVersion + "}");

        JsonNode merge = lisa.json(lisa.postJson("/api/labels/" + longNameId + "/merge",
                "{\"targetId\":\"" + telekomLabelId + "\",\"expectedVersion\":" + labelVersion(lisa, longNameId)
                        + "}"));

        assertThat(merge.path("documentsUpdated").asInt()).isEqualTo(1);
        assertThat(merge.path("target").path("aliases")).anySatisfy(alias ->
                assertThat(alias.asText()).isEqualTo("Telekom Deutschland GmbH"));
        assertThat(document(lisa, telekomDocumentId).path("classification").path("labels"))
                .anySatisfy(chip -> assertThat(chip.path("labelId").asText()).isEqualTo(telekomLabelId));
        assertThat(ids(lisa, "/api/documents?organization=" + telekomLabelId)).contains(telekomDocumentId);
        assertThat(ids(lisa, "/api/documents?organization=" + longNameId))
                .as("old filter links keep working through the merge redirect").contains(telekomDocumentId);
        assertThat(lisa.json(lisa.get("/api/labels/resolve?facet=ORGANIZATION&name=Telekom%20Deutschland%20GmbH"))
                .path("id").asText()).isEqualTo(telekomLabelId);
    }

    @Test
    @Order(8)
    void retiredLabelsStaySearchableAndUnusedLabelsCanBeDeleted() {
        ApiClient lisa = loggedIn(ANNA);
        String tagId = lisa.json(lisa.postJson("/api/labels", "{\"facet\":\"TAG\",\"name\":\"Glasfaser\"}"))
                .path("id").asText();

        lisa.postJson("/api/labels/" + tagId + "/retire", "{\"expectedVersion\":" + labelVersion(lisa, tagId)
                + "}");
        long classificationVersion = document(lisa, unsortedDocumentId).path("classification").path("version")
                .asLong();
        ApiClient.Response rejected = lisa.putJson("/api/documents/" + unsortedDocumentId + "/labels",
                "{\"labelIds\":[\"" + tagId + "\"],\"expectedVersion\":" + classificationVersion + "}");
        assertThat(rejected.status()).isEqualTo(400);
        assertThat(lisa.json(rejected).path("code").asText()).isEqualTo("label.retired");

        assertThat(lisa.delete("/api/labels/" + tagId + "?expectedVersion=" + labelVersion(lisa, tagId)).status())
                .isEqualTo(204);
        assertThat(lisa.get("/api/labels/resolve?facet=TAG&name=Glasfaser").status()).isEqualTo(404);
        ApiClient.Response builtinDelete = lisa.delete("/api/labels/" + invoiceLabelId + "?expectedVersion="
                + labelVersion(lisa, invoiceLabelId));
        assertThat(builtinDelete.status()).isEqualTo(400);
        assertThat(lisa.json(builtinDelete).path("code").asText()).isIn("label.builtin", "label.inUse");
    }

    @Test
    @Order(9)
    void suggestionsAreVisibleAndCanBeAcceptedOrRejected() {
        ApiClient lisa = loggedIn(ANNA);
        String reminder = builtinLabel(lisa, "paymentReminder");
        long classificationVersion = document(lisa, unsortedDocumentId).path("classification").path("version")
                .asLong();

        // Automatic classification (#23) is not connected yet; the review flow is exercised through the
        // application use case that a classifier will call.
        var outcome = propose(unsortedDocumentId, classificationVersion, reminder);
        assertThat(outcome.suggested()).isEqualTo(1);

        JsonNode withSuggestion = document(lisa, unsortedDocumentId);
        assertThat(withSuggestion.path("classification").path("labels")).singleElement().satisfies(chip -> {
            assertThat(chip.path("reviewState").asText()).isEqualTo("SUGGESTED");
            assertThat(chip.path("origin").asText()).isEqualTo("AUTOMATIC");
            assertThat(chip.path("confidence").asDouble()).isEqualTo(0.55);
        });
        assertThat(ids(lisa, "/api/documents?type=" + reminder))
                .as("suggestions are visible but do not filter").doesNotContain(unsortedDocumentId);

        JsonNode accepted = lisa.json(lisa.postJson("/api/documents/" + unsortedDocumentId + "/labels/" + reminder
                + "/accept", "{\"expectedVersion\":"
                + withSuggestion.path("classification").path("version").asLong() + "}"));
        assertThat(accepted.path("classification").path("labels")).singleElement()
                .satisfies(chip -> assertThat(chip.path("reviewState").asText()).isEqualTo("CONFIRMED"));
        assertThat(ids(lisa, "/api/documents?type=" + reminder)).contains(unsortedDocumentId);

        JsonNode rejectedAgain = lisa.json(lisa.postJson("/api/documents/" + unsortedDocumentId + "/labels/"
                + reminder + "/reject", "{\"expectedVersion\":"
                + accepted.path("classification").path("version").asLong() + "}"));
        assertThat(rejectedAgain.path("classification").path("labels")).isEmpty();
        assertThat(propose(unsortedDocumentId, document(lisa, unsortedDocumentId).path("classification")
                .path("version").asLong(), reminder).skipped())
                .as("a rejected value is not proposed again").isEqualTo(1);
    }

    @Test
    @Order(10)
    void bulkPlacementFilesSeveralDocumentsAtOnce() {
        ApiClient lisa = loggedIn(ANNA);
        long classificationVersion = document(lisa, unsortedDocumentId).path("classification").path("version")
                .asLong();

        JsonNode moved = lisa.json(lisa.postJson("/api/documents/placement", "{\"categoryId\":\"" + houseId
                + "\",\"documents\":[{\"documentId\":\"" + unsortedDocumentId + "\",\"expectedVersion\":"
                + classificationVersion + "}]}"));

        assertThat(moved.path("moved").asInt()).isEqualTo(1);
        assertThat(document(lisa, unsortedDocumentId).path("classification").path("path")).singleElement()
                .satisfies(step -> assertThat(step.path("name").asText()).isEqualTo("Haus"));
        assertThat(ids(lisa, "/api/documents?category=unsorted")).doesNotContain(unsortedDocumentId);
    }

    @Test
    @Order(11)
    void uploadsIntoAMissingFolderAreRejectedBeforeAnythingIsStored() {
        ApiClient lisa = loggedIn(ANNA);
        long before = lisa.json(lisa.get("/api/documents")).path("totalDocuments").asLong();

        ApiClient.Response rejected = lisa.upload("/api/documents?categoryId=" + java.util.UUID.randomUUID(),
                "Nirgendwo.pdf", pdf("nowhere"), List.of());

        assertThat(rejected.status()).isEqualTo(404);
        assertThat(lisa.json(rejected).path("code").asText()).isEqualTo("category.notFound");
        assertThat(lisa.json(lisa.get("/api/documents")).path("totalDocuments").asLong()).isEqualTo(before);
    }

    @Test
    @Order(12)
    void archivedDocumentsStayContentSearchableWithFacets() {
        ApiClient lisa = loggedIn(ANNA);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(
                document(lisa, telekomDocumentId).path("processing").path("state").asText()).isEqualTo("ARCHIVED"));

        JsonNode result = lisa.json(lisa.get("/api/documents?query=telekom&organization=" + telekomLabelId));
        assertThat(result.path("items")).hasSize(1);
        assertThat(result.path("notContentIndexed").asLong()).isZero();
    }

    private long labelVersion(ApiClient client, String labelId) {
        for (JsonNode label : client.json(client.get("/api/labels"))) {
            if (labelId.equals(label.path("id").asText())) {
                return label.path("version").asLong();
            }
        }
        throw new IllegalStateException("label " + labelId + " is missing");
    }

    private List<String> ids(ApiClient client, String path) {
        ApiClient.Response response = client.get(path);
        assertThat(response.status()).as(path + " -> " + response.body()).isEqualTo(200);
        List<String> ids = new java.util.ArrayList<>();
        client.json(response).path("items").forEach(item -> ids.add(item.path("id").asText()));
        return ids;
    }

    /** Stands in for the classifier of issue #23, which calls the same application use case. */
    private ProposeClassification.ProposalOutcome propose(String documentId, long version, String labelId) {
        Actor actor = new Actor(credentials.findCredentials(ANNA).orElseThrow().id());
        return proposals.propose(actor, new ProposeClassification.ProposalCommand(DocumentId.of(documentId),
                version, "run:it", List.of(new ProposeClassification.LabelProposal(
                        java.util.UUID.fromString(labelId), 0.55, false))));
    }
}
