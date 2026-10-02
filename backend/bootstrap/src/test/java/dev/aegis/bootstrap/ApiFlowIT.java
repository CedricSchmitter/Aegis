package dev.aegis.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.aegis.bootstrap.support.ApiClient;
import dev.aegis.bootstrap.support.FakePaperlessArchive;
import dev.aegis.bootstrap.support.PostgresIT;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;

/**
 * End-to-end API flow of the first runnable test against PostgreSQL: security, durable capture, archival,
 * shared visibility, source-linked tasks with conflict detection, notices, status, export and outage behavior.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiFlowIT extends PostgresIT {
    @LocalServerPort
    int port;

    @Autowired
    FakePaperlessArchive archive;

    private static String documentId;
    private static String taskId;
    private static long taskVersion;
    private static String annaId;
    private static String benId;

    private ApiClient client() {
        return new ApiClient(port);
    }

    private ApiClient loggedIn(String username) {
        ApiClient client = client();
        assertThat(client.login(username, PASSWORD)).as("login " + username).isTrue();
        return client;
    }

    @Test
    @Order(1)
    void unauthenticatedAndWrongCredentialsAreRejectedWithProblemDetails() {
        ApiClient anonymous = client();
        ApiClient.Response documents = anonymous.get("/api/documents");
        assertThat(documents.status()).isEqualTo(401);
        assertThat(anonymous.json(documents).path("code").asText()).isEqualTo("auth.required");

        assertThat(anonymous.get("/api/system/version").status()).isEqualTo(200);
        assertThat(anonymous.get("/actuator/health").status()).isEqualTo(200);

        anonymous.get("/api/auth/session");
        ApiClient.Response wrong = anonymous.postJson("/api/auth/login",
                "{\"username\":\"anna\",\"password\":\"wrong-password\"}");
        assertThat(wrong.status()).isEqualTo(401);
        assertThat(anonymous.json(wrong).path("code").asText()).isEqualTo("auth.invalidCredentials");

        ApiClient.Response noCsrf = anonymous.postWithoutCsrf("/api/auth/login",
                "{\"username\":\"anna\",\"password\":\"" + PASSWORD + "\"}");
        assertThat(noCsrf.status()).isEqualTo(403);
        assertThat(anonymous.json(noCsrf).path("code").asText()).isEqualTo("csrf.invalid");
    }

    @Test
    @Order(1)
    void aShareThatMissesTheServiceWorkerIsSentToTheSharePageWithoutBeingProcessed() {
        ApiClient.Response share = client().postWithoutCsrf("/share-target", "{}");
        assertThat(share.status()).isEqualTo(303);
        assertThat(share.header("Location")).isEqualTo("/share?error=unavailable");
    }

    @Test
    @Order(2)
    void bothMembersCanLogInAndSeeEachOther() {
        ApiClient anna = loggedIn(ANNA);
        JsonNode session = anna.json(anna.get("/api/auth/session"));
        assertThat(session.path("member").path("username").asText()).isEqualTo("anna");
        assertThat(session.path("member").path("locale").asText()).isEqualTo("DE");
        annaId = session.path("member").path("id").asText();

        // Two persons and the assistant account, which the startup designation renamed to "Aegis" (ADR-0017).
        JsonNode members = anna.json(anna.get("/api/members"));
        assertThat(members).hasSize(3);
        assertThat(members).extracting(member -> member.path("kind").asText())
                .containsExactlyInAnyOrder("PERSON", "PERSON", "ASSISTANT");
        assertThat(members).filteredOn(member -> "helper".equals(member.path("username").asText())).singleElement()
                .satisfies(helper -> assertThat(helper.path("displayName").asText()).isEqualTo("Aegis"));
        assertThat(session.path("member").path("kind").asText()).isEqualTo("PERSON");
        benId = loggedIn(BEN).json(loggedIn(BEN).get("/api/auth/session")).path("member").path("id").asText();
        assertThat(benId).isNotEqualTo(annaId);

        JsonNode switched = anna.json(anna.putJson("/api/members/me/locale", "{\"locale\":\"EN\"}"));
        assertThat(switched.path("locale").asText()).isEqualTo("EN");
        assertThat(anna.json(anna.putJson("/api/members/me/locale", "{\"locale\":\"DE\"}")).path("locale").asText())
                .isEqualTo("DE");
    }

    @Test
    @Order(3)
    void uploadIsDurableThenArchivedAndOriginalIsUnchanged() throws Exception {
        ApiClient anna = loggedIn(ANNA);
        byte[] pdf = fixtureBytes();

        ApiClient.Response upload = anna.upload("/api/documents", "Stromvertrag Musterhaushalt.pdf", pdf,
                List.of("HOUSE", "MEMBER:" + benId));
        assertThat(upload.status()).as(upload.body()).isEqualTo(201);
        JsonNode created = anna.json(upload);
        assertThat(created.path("duplicate").asBoolean()).isFalse();
        documentId = created.path("document").path("id").asText();
        assertThat(created.path("document").path("processing").path("state").asText()).isEqualTo("RECEIVED");
        assertThat(created.path("document").path("checksumSha256").asText()).isEqualTo(sha256(pdf));
        assertThat(created.path("document").path("subjects")).hasSize(2);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            JsonNode document = anna.json(anna.get("/api/documents/" + documentId));
            assertThat(document.path("processing").path("state").asText()).isEqualTo("ARCHIVED");
            assertThat(document.path("processing").path("paperlessDocumentId").isNumber()).isTrue();
        });

        ApiClient.Response original = anna.get("/api/documents/" + documentId + "/original");
        assertThat(original.status()).isEqualTo(200);
        assertThat(original.bytes()).isEqualTo(pdf);
        assertThat(original.header("X-Aegis-Original-Source")).isEqualTo("ARCHIVE");
        assertThat(original.header("Content-Disposition")).contains("Stromvertrag");

        // The upload already confirmed saving: a successful archival adds no notice.
        JsonNode notices = anna.json(anna.get("/api/notices"));
        assertThat(notices).noneSatisfy(n -> assertThat(n.path("linkId").asText()).isEqualTo(documentId));
    }

    @Test
    @Order(4)
    void duplicateUploadByTheOtherMemberReturnsTheExistingRecordWithoutNewWork() throws IOException {
        ApiClient ben = loggedIn(BEN);
        int submissionsBefore = archive.submissions.get();
        long documentsBefore = ben.json(ben.get("/api/documents")).path("totalDocuments").asLong();

        ApiClient.Response again = ben.upload("/api/documents", "copy.pdf", fixtureBytes(), List.of());

        assertThat(again.status()).isEqualTo(200);
        JsonNode body = ben.json(again);
        assertThat(body.path("duplicate").asBoolean()).isTrue();
        assertThat(body.path("document").path("id").asText()).isEqualTo(documentId);
        assertThat(ben.json(ben.get("/api/documents")).path("totalDocuments").asLong()).isEqualTo(documentsBefore);
        assertThat(archive.submissions.get()).isEqualTo(submissionsBefore);
    }

    @Test
    @Order(5)
    void searchCombinesTitleAndContentAndBothMembersSeeTheCollection() {
        ApiClient ben = loggedIn(BEN);
        // Other IT classes share the database, so assertions look for this test's document rather than exact counts.
        JsonNode byTitle = ben.json(ben.get("/api/documents?query=musterhaushalt"));
        assertThat(byTitle.path("items")).extracting(n -> n.path("id").asText()).contains(documentId);
        assertThat(byTitle.path("contentSearch").asText()).isEqualTo("READY");

        JsonNode byContent = ben.json(ben.get("/api/documents?query=Kuendigungsfrist"));
        assertThat(byContent.path("items")).extracting(n -> n.path("id").asText()).contains(documentId);

        JsonNode nothing = ben.json(ben.get("/api/documents?query=zzz-not-there"));
        assertThat(nothing.path("items")).isEmpty();
        assertThat(nothing.path("contentSearch").asText()).isEqualTo("READY");
    }

    @Test
    @Order(6)
    void linkedTaskAssignmentNotifiesAndConflictsAreDetectedNotOverwritten() {
        ApiClient ben = loggedIn(BEN);
        ApiClient anna = loggedIn(ANNA);
        long annaUnreadBefore = anna.json(anna.get("/api/notices/unread-count")).path("unread").asLong();
        // Other test classes may have left notices for Ben; assigning Anna's task must not add one.
        long benUnreadBefore = ben.json(ben.get("/api/notices/unread-count")).path("unread").asLong();

        ApiClient.Response created = ben.postJson("/api/tasks", "{\"title\":\"Stromvertrag kündigen\","
                + "\"note\":\"Frist prüfen\",\"ownerId\":\"" + annaId + "\",\"dueDate\":\"2026-12-31\","
                + "\"plannedDate\":\"2026-10-01\",\"sourceDocumentId\":\"" + documentId + "\"}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        JsonNode task = ben.json(created);
        taskId = task.path("id").asText();
        taskVersion = task.path("version").asLong();
        assertThat(task.path("source").path("documentTitle").asText()).isEqualTo("Stromvertrag Musterhaushalt");

        JsonNode annaNotices = anna.json(anna.get("/api/notices?unreadOnly=true"));
        assertThat(annaNotices).anySatisfy(n -> {
            assertThat(n.path("kind").asText()).isEqualTo("TASK_ASSIGNED");
            assertThat(n.path("linkId").asText()).isEqualTo(taskId);
        });
        assertThat(anna.json(anna.get("/api/notices/unread-count")).path("unread").asLong())
                .isEqualTo(annaUnreadBefore + 1);
        assertThat(ben.json(ben.get("/api/notices/unread-count")).path("unread").asLong()).isEqualTo(benUnreadBefore);

        JsonNode planned = anna.json(anna.postJson("/api/tasks/" + taskId + "/plan",
                "{\"plannedDate\":\"2026-11-15\",\"expectedVersion\":" + taskVersion + "}"));
        assertThat(planned.path("plannedDate").asText()).isEqualTo("2026-11-15");
        assertThat(planned.path("dueDate").asText()).isEqualTo("2026-12-31");

        ApiClient.Response stale = ben.postJson("/api/tasks/" + taskId + "/complete",
                "{\"expectedVersion\":" + taskVersion + "}");
        assertThat(stale.status()).isEqualTo(409);
        assertThat(ben.json(stale).path("code").asText()).isEqualTo("version.conflict");

        JsonNode done = ben.json(ben.postJson("/api/tasks/" + taskId + "/complete",
                "{\"expectedVersion\":" + planned.path("version").asLong() + "}"));
        assertThat(done.path("status").asText()).isEqualTo("DONE");
        assertThat(done.path("completedBy").asText()).isEqualTo(benId);

        JsonNode annaView = anna.json(anna.get("/api/tasks?includeDone=true"));
        assertThat(annaView).anySatisfy(t -> assertThat(t.path("id").asText()).isEqualTo(taskId));
        assertThat(anna.json(anna.get("/api/tasks?documentId=" + documentId))).hasSize(1);

        JsonNode today = anna.json(anna.get("/api/today"));
        assertThat(today.path("calendar").path("connected").asBoolean()).isFalse();
        assertThat(today.path("date").asText()).isNotEmpty();

        JsonNode read = anna.json(anna.postJson("/api/notices/read-all", "{}"));
        assertThat(read.path("marked").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(anna.json(anna.get("/api/notices/unread-count")).path("unread").asLong()).isZero();
    }

    @Test
    @Order(7)
    void statusAndExportAreTruthful() {
        ApiClient anna = loggedIn(ANNA);
        JsonNode status = anna.json(anna.get("/api/status"));
        // The overview answers "is everything OK?" with stable codes; integrations that are not set up (calendar,
        // mail, push) are not listed, while a missing backup is a problem. The test gateway sets up AI.
        JsonNode checks = status.path("checks");
        assertThat(checks).extracting(c -> c.path("area").asText())
                .startsWith("database", "archive", "backup", "storage", "ai")
                .doesNotContain("calendar", "mail", "webPush", "telegram", "export", "externalBackup");
        assertThat(checks.get(0).path("state").asText()).isEqualTo("OK");
        assertThat(checks.get(0).path("reason").asText()).isEqualTo("database.connected");
        assertThat(checks.get(0).path("lastSuccessAt").isNull()).as("a connected database has a last success")
                .isFalse();
        assertThat(checks.get(1).path("state").asText()).isEqualTo("OK");
        assertThat(checks.get(1).path("reason").asText()).isEqualTo("archive.reachable");
        assertThat(checks.get(2).path("state").asText()).isEqualTo("PROBLEM");
        assertThat(checks.get(2).path("reason").asText()).isEqualTo("backup.notConfigured");
        assertThat(checks).allSatisfy(c -> assertThat(c.has("detail")).isFalse());
        assertThat(status.has("integrations")).isFalse();
        assertThat(status.path("documentProcessing").path("ARCHIVED").asLong()).isGreaterThanOrEqualTo(1);

        ApiClient.Response export = anna.postJson("/api/exports", "{}");
        assertThat(export.status()).as(export.body()).isEqualTo(201);
        JsonNode generation = anna.json(export);
        assertThat(generation.path("documentCount").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(generation.path("taskCount").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(generation.path("missingOriginals")).isEmpty();
        assertThat(anna.json(anna.get("/api/exports"))).isNotEmpty();
    }

    @Test
    @Order(9)
    void malformedRequestsAreClientErrorsAndLogoutEndsAccess() {
        ApiClient anna = loggedIn(ANNA);
        for (ApiClient.Response response : List.of(
                anna.postJson("/api/tasks", "{"),
                anna.postJson("/api/tasks", "{\"title\":\"unknown owner\",\"ownerId\":\""
                        + java.util.UUID.randomUUID() + "\"}"),
                anna.postJson("/api/tasks", "{\"title\":\"invalid date\",\"dueDate\":\"tomorrow\"}"),
                anna.get("/api/documents/not-a-uuid"))) {
            assertThat(response.status()).as(response.body()).isEqualTo(400);
            assertThat(anna.json(response).path("code").asText()).isEqualTo("validation.failed");
        }
        assertThat(anna.postJson("/api/auth/logout", "{}").status()).isEqualTo(204);
        assertThat(anna.get("/api/documents").status()).isEqualTo(401);
    }

    @Test
    @Order(8)
    void archiveOutageDegradesHonestlyAndRetriesLater() throws Exception {
        ApiClient anna = loggedIn(ANNA);
        archive.available = false;
        try {
            JsonNode degraded = anna.json(anna.get("/api/documents?query=musterhaushalt"));
            assertThat(degraded.path("contentSearch").asText()).isEqualTo("UNAVAILABLE");
            assertThat(degraded.path("items")).extracting(n -> n.path("id").asText()).contains(documentId);

            ApiClient.Response original = anna.get("/api/documents/" + documentId + "/original");
            assertThat(original.status()).isEqualTo(200);
            assertThat(original.header("X-Aegis-Original-Source")).isEqualTo("STAGING");

            JsonNode status = anna.json(anna.get("/api/status"));
            assertThat(status.path("checks")).anySatisfy(c -> {
                assertThat(c.path("area").asText()).isEqualTo("archive");
                assertThat(c.path("state").asText()).isEqualTo("PROBLEM");
                assertThat(c.path("reason").asText()).isEqualTo("archive.unavailable");
            });

            byte[] second = ("%PDF-1.4\n% second synthetic document " + System.nanoTime() + "\n")
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            JsonNode uploaded = anna.json(anna.upload("/api/documents", "zweites.pdf", second, List.of()));
            String secondId = uploaded.path("document").path("id").asText();
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                JsonNode doc = anna.json(anna.get("/api/documents/" + secondId));
                assertThat(doc.path("processing").path("attempts").asInt()).isGreaterThanOrEqualTo(1);
                assertThat(doc.path("processing").path("state").asText()).isEqualTo("RECEIVED");
                assertThat(doc.path("processing").path("lastErrorCode").asText()).isEqualTo("paperless.unavailable");
                assertThat(doc.path("processing").path("retryScheduled").asBoolean()).isTrue();
            });
            archive.available = true;
            // The job still exists, so the scheduled retry (after aegis.archival.retry-base, 2s in the tests) archives
            // the document; nothing reschedules it by hand.
            await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> assertThat(
                    anna.json(anna.get("/api/documents/" + secondId)).path("processing").path("state").asText())
                    .isEqualTo("ARCHIVED"));
        } finally {
            archive.available = true;
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
