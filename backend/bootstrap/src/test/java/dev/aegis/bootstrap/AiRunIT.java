package dev.aegis.bootstrap;

import static dev.aegis.bootstrap.support.ControlledModelGateway.held;
import static dev.aegis.bootstrap.support.ControlledModelGateway.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.aegis.assistant.adapters.tools.DocumentSearchTool;
import dev.aegis.assistant.application.RunExecutor;
import dev.aegis.assistant.application.api.RequestAiRun;
import dev.aegis.assistant.application.api.RunRequest;
import dev.aegis.assistant.application.api.RunView;
import dev.aegis.assistant.application.api.SourceVersion;
import dev.aegis.assistant.application.ports.ContentPart;
import dev.aegis.assistant.application.ports.ModelRequest;
import dev.aegis.assistant.domain.RunId;
import dev.aegis.bootstrap.support.ApiClient;
import dev.aegis.bootstrap.support.ControlledModelGateway;
import dev.aegis.bootstrap.support.PostgresIT;
import dev.aegis.identity.application.api.MemberCredentialsQuery;
import dev.aegis.shared.Actor;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * The AI execution boundary against PostgreSQL with the real job scheduler, REST API, documents context and
 * authorization, and a controlled model: durable runs, visible state, exactly-once effects under restart and
 * cancellation, least-privilege tools and unaffected capture/search/tasks during a model outage.
 */
class AiRunIT extends PostgresIT {
    @LocalServerPort
    int port;
    @Autowired
    ControlledModelGateway model;
    @Autowired
    RequestAiRun requests;
    @Autowired
    RunExecutor executor;
    @Autowired
    MemberCredentialsQuery members;
    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void resetModel() {
        model.reset();
    }

    private ApiClient loggedIn(String username) {
        ApiClient client = new ApiClient(port);
        assertThat(client.login(username, PASSWORD)).isTrue();
        return client;
    }

    private Actor actor(String username) {
        return new Actor(members.findCredentials(username).orElseThrow().id());
    }

    private JsonNode run(ApiClient client, String id) {
        ApiClient.Response response = client.get("/api/ai/runs/" + id);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return client.json(response);
    }

    private static byte[] uniquePdf(String marker) {
        return ("%PDF-1.4\n% " + marker + " " + UUID.randomUUID() + "\n").getBytes(StandardCharsets.US_ASCII);
    }

    /** Uploads a document and creates a tag; returns document id, classification version and tag id. */
    private String[] documentAndTag(ApiClient client, String title) {
        ApiClient.Response upload = client.upload("/api/documents", title + ".pdf", uniquePdf(title), List.of());
        assertThat(upload.status()).as(upload.body()).isEqualTo(201);
        JsonNode document = client.json(upload).path("document");
        String tag = client.json(client.postJson("/api/labels",
                "{\"facet\":\"TAG\",\"name\":\"ai-it-" + UUID.randomUUID() + "\"}")).path("id").asText();
        return new String[] {document.path("id").asText(),
                String.valueOf(document.path("classification").path("version").asLong()), tag};
    }

    private RunView classify(String documentId, long version) {
        return requests.request(actor(ANNA), new RunRequest("DOCUMENT_REVIEW",
                "it-review:" + documentId + ":" + version,
                List.of(new SourceVersion("document", documentId, version)), Map.of(), false));
    }

    private List<JsonNode> automaticLabels(ApiClient client, String documentId) {
        JsonNode document = client.json(client.get("/api/documents/" + documentId));
        List<JsonNode> labels = new java.util.ArrayList<>();
        document.path("classification").path("labels").forEach(chip -> {
            if ("AUTOMATIC".equals(chip.path("origin").asText())) {
                labels.add(chip);
            }
        });
        return labels;
    }

    private long ledgerRows(String documentId) {
        return jdbc.sql("SELECT count(*) FROM ai_run_effects WHERE effect_key LIKE ?")
                .param("it-review:" + documentId + ":%").query(Long.class).single();
    }

    @Test
    void connectionCheckRunsAsADurableJobAndIsVisibleToBothMembers() {
        ApiClient anna = loggedIn(ANNA);
        ApiClient ben = loggedIn(BEN);
        model.answer(json(Map.of("status", "ok")));

        ApiClient.Response started = anna.postJson("/api/ai/connection-checks", null);

        assertThat(started.status()).as(started.body()).isEqualTo(202);
        JsonNode checks = anna.json(started);
        assertThat(checks).hasSize(1);
        assertThat(checks.get(0).path("workload").asText()).isEqualTo("CONNECTION_CHECK");
        assertThat(checks.get(0).path("model").asText()).isEqualTo(ControlledModelGateway.MODEL);
        String id = checks.get(0).path("id").asText();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(run(ben, id).path("state").asText()).isEqualTo("SUCCEEDED"));

        JsonNode done = run(ben, id);
        assertThat(done.path("output").asText()).isEqualTo("{\"status\":\"ok\"}");
        assertThat(done.path("cost").decimalValue()).isPositive();
        assertThat(done.path("steps")).extracting(step -> step.path("kind").asText())
                .containsExactly("ATTEMPT_STARTED", "MODEL_CALL", "COMPLETED");
        assertThat(model.requests.getFirst().tools()).isEmpty();

        JsonNode overview = anna.json(anna.get("/api/ai/overview"));
        assertThat(overview.path("configured").asBoolean()).isTrue();
        assertThat(overview.path("spentToday").decimalValue()).isPositive();
        assertThat(overview.path("workloads")).anySatisfy(workload -> {
            assertThat(workload.path("workload").asText()).isEqualTo("CHAT");
            assertThat(workload.path("available").asBoolean()).isFalse();
        });
        JsonNode status = anna.json(anna.get("/api/status"));
        assertThat(status.path("checks")).anySatisfy(check -> {
            assertThat(check.path("area").asText()).isEqualTo("ai");
            assertThat(check.path("state").asText()).isEqualTo("OK");
            assertThat(check.path("reason").asText()).isEqualTo("ai.connected");
        });
        assertThat(new ApiClient(port).get("/api/ai/runs").status()).isEqualTo(401);
    }

    @Test
    void revivedExecutionAppliesTheSuggestionOnceWhileTheHangingAttemptIsDiscarded() throws Exception {
        ApiClient anna = loggedIn(ANNA);
        String[] fixture = documentAndTag(anna, "AI Restart Probe");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        model.answer(held(entered, release, json(Map.of("labelId", fixture[2]))))
                .answer(json(Map.of("labelId", fixture[2])));
        RunView requested = classify(fixture[0], Long.parseLong(fixture[1]));
        assertThat(entered.await(20, TimeUnit.SECONDS)).as("job thread reached the model").isTrue();

        // A revived job execution (as after a process restart) starts the next attempt and completes it.
        RunExecutor.Outcome revived = executor.execute(new RunId(requested.id()));
        release.countDown();

        assertThat(revived).isInstanceOf(RunExecutor.Done.class);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            JsonNode run = run(anna, requested.id().toString());
            assertThat(run.path("state").asText()).isEqualTo("SUCCEEDED");
            assertThat(run.path("modelCalls").asInt()).isEqualTo(2);
        });
        JsonNode run = run(anna, requested.id().toString());
        assertThat(run.path("attempt").asInt()).isEqualTo(2);
        assertThat(run.path("steps")).extracting(step -> step.path("outcome").asText())
                .contains("attempt.interrupted", "run.succeeded");
        assertThat(automaticLabels(anna, fixture[0])).singleElement().satisfies(chip -> {
            assertThat(chip.path("labelId").asText()).isEqualTo(fixture[2]);
            assertThat(chip.path("reviewState").asText()).isEqualTo("SUGGESTED");
            assertThat(chip.path("sourceRef").asText()).isEqualTo("ai-run:" + requested.id());
        });
        assertThat(ledgerRows(fixture[0])).isEqualTo(1);
    }

    @Test
    void cancellingWhileTheModelWorksLeavesTheDocumentUnchanged() throws Exception {
        ApiClient anna = loggedIn(ANNA);
        ApiClient ben = loggedIn(BEN);
        String[] fixture = documentAndTag(anna, "AI Cancel Probe");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        model.answer(held(entered, release, json(Map.of("labelId", fixture[2]))));
        RunView requested = classify(fixture[0], Long.parseLong(fixture[1]));
        assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue();

        ApiClient.Response cancel = ben.postJson("/api/ai/runs/" + requested.id() + "/cancel", null);
        assertThat(cancel.status()).as(cancel.body()).isEqualTo(200);
        assertThat(ben.json(cancel).path("cancelRequested").asBoolean()).isTrue();
        assertThat(ben.json(cancel).path("state").asText()).isEqualTo("RUNNING");
        release.countDown();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(run(anna, requested.id().toString()).path("state").asText()).isEqualTo("CANCELLED"));
        assertThat(run(anna, requested.id().toString()).path("cost").decimalValue()).isPositive();
        assertThat(automaticLabels(anna, fixture[0])).isEmpty();
        assertThat(ledgerRows(fixture[0])).isZero();
    }

    @Test
    void toolRunsAsTheRequestingMemberAndReturnsMetadataOnly() {
        ApiClient anna = loggedIn(ANNA);
        String probe = "AI Tool Probe " + UUID.randomUUID().toString().substring(0, 8);
        String[] fixture = documentAndTag(anna, probe);
        model.answer(ControlledModelGateway.toolCall("t1", DocumentSearchTool.NAME, Map.of("query", probe)))
                .answer(json(Map.of("labelId", fixture[2])));

        RunView requested = classify(fixture[0], Long.parseLong(fixture[1]));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(run(anna, requested.id().toString()).path("state").asText()).isEqualTo("SUCCEEDED"));
        assertThat(run(anna, requested.id().toString()).path("toolCalls").asInt()).isEqualTo(1);
        ModelRequest first = model.requests.getFirst();
        assertThat(first.tools()).extracting(tool -> tool.name()).containsExactly(DocumentSearchTool.NAME);
        assertThat(((ContentPart.Text) first.messages().getFirst().parts().getFirst()).text())
                .contains("<untrusted_content source=\"document:" + fixture[0] + "\">").contains(probe);
        ContentPart.ToolResult result = (ContentPart.ToolResult) model.requests.get(1).messages().getLast().parts()
                .getFirst();
        assertThat(result.error()).isFalse();
        assertThat(result.content()).contains(fixture[0]).contains(probe).doesNotContain("%PDF");
        assertThat(automaticLabels(anna, fixture[0])).hasSize(1);
    }

    @Test
    void modelOutageLeavesCaptureSearchAndTasksWorking() {
        ApiClient anna = loggedIn(ANNA);
        String[] fixture = documentAndTag(anna, "AI Outage Probe");
        RunView requested = classify(fixture[0], Long.parseLong(fixture[1]));
        try {
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                JsonNode run = run(anna, requested.id().toString());
                assertThat(run.path("state").asText()).isEqualTo("WAITING_RETRY");
                assertThat(run.path("failureCategory").asText()).isEqualTo("PROVIDER_UNAVAILABLE");
            });

            ApiClient.Response upload = anna.upload("/api/documents", "Outage capture.pdf",
                    uniquePdf("outage capture"), List.of());
            ApiClient.Response search = anna.get("/api/documents?query=Outage");
            ApiClient.Response task = anna.postJson("/api/tasks", "{\"title\":\"Während KI-Ausfall anlegen\"}");

            assertThat(upload.status()).as(upload.body()).isEqualTo(201);
            assertThat(search.status()).isEqualTo(200);
            assertThat(anna.json(search).path("items")).isNotEmpty();
            assertThat(task.status()).as(task.body()).isEqualTo(201);
            assertThat(automaticLabels(anna, fixture[0])).isEmpty();
        } finally {
            anna.postJson("/api/ai/runs/" + requested.id() + "/cancel", null);
        }
    }

    /** Regression for issue #59 against PostgreSQL: concurrent requests with one key never run different work. */
    @Test
    void concurrentRequestsReusingAKeyForDifferentWorkAreRejected() throws Exception {
        ApiClient anna = loggedIn(ANNA);
        String[] fixture = documentAndTag(anna, "AI Key Probe");
        long version = Long.parseLong(fixture[1]);
        String key = "it-review:key-conflict:" + UUID.randomUUID();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<Object>> results = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            long analyzed = version + (i % 2);
            String member = i % 2 == 0 ? ANNA : BEN;
            results.add(pool.submit(() -> {
                start.await();
                try {
                    return requests.request(actor(member), new RunRequest("DOCUMENT_REVIEW", key,
                            List.of(new SourceVersion("document", fixture[0], analyzed)), Map.of(), false))
                            .sources().getFirst().version() + ":" + "ok";
                } catch (dev.aegis.shared.InvalidInputException rejected) {
                    return rejected.code();
                }
            }));
        }
        start.countDown();
        List<Object> outcomes = new java.util.ArrayList<>();
        for (var result : results) {
            outcomes.add(result.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        try {
            assertThat(outcomes).filteredOn(outcome -> outcome.toString().endsWith(":ok")).hasSize(2)
                    .extracting(Object::toString).containsOnly(outcomes.stream().map(Object::toString)
                            .filter(outcome -> outcome.endsWith(":ok")).findFirst().orElseThrow());
            assertThat(outcomes).filteredOn("ai.run.keyConflict"::equals).hasSize(2);
            assertThat(jdbc.sql("SELECT count(*) FROM ai_runs WHERE idempotency_key = ?").param(key)
                    .query(Long.class).single()).isEqualTo(1);
        } finally {
            jdbc.sql("SELECT id FROM ai_runs WHERE idempotency_key = ?").param(key).query(UUID.class).list()
                    .forEach(id -> anna.postJson("/api/ai/runs/" + id + "/cancel", null));
        }
    }
}
