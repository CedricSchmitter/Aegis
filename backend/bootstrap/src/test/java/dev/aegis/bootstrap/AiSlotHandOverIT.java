package dev.aegis.bootstrap;

import static dev.aegis.bootstrap.support.ControlledModelGateway.held;
import static dev.aegis.bootstrap.support.ControlledModelGateway.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.aegis.assistant.application.AiSettings;
import dev.aegis.assistant.application.api.RequestAiRun;
import dev.aegis.assistant.application.api.RunRequest;
import dev.aegis.assistant.application.api.SourceVersion;
import dev.aegis.bootstrap.support.ApiClient;
import dev.aegis.bootstrap.support.ControlledModelGateway;
import dev.aegis.bootstrap.support.PostgresIT;
import dev.aegis.identity.application.api.MemberCredentialsQuery;
import dev.aegis.shared.Actor;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
 * Hand-over of the household's single AI slot (ADR-0023) against PostgreSQL and the real job scheduler,
 * with production's slot wait: runs queued behind a running one start right after it ends, oldest request first,
 * instead of one run per slot wait.
 */
class AiSlotHandOverIT extends PostgresIT {
    private static final int QUEUED = 4;
    /** How long each queued run's model call takes. */
    private static final Duration CALL = Duration.ofMillis(300);

    @LocalServerPort
    int port;
    @Autowired
    ControlledModelGateway model;
    @Autowired
    RequestAiRun requests;
    @Autowired
    MemberCredentialsQuery members;
    @Autowired
    AiSettings settings;
    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void resetModel() {
        model.reset();
    }

    private record Started(Instant startedAt, Instant finishedAt, int attempt) {
    }

    @Test
    void queuedRunsStartRightAfterEachOtherOldestFirst() throws Exception {
        assertThat(settings.maxConcurrentRuns()).isEqualTo(1);
        assertThat(settings.slotWait()).as("production's slot wait").isGreaterThanOrEqualTo(Duration.ofSeconds(15));
        ApiClient anna = new ApiClient(port);
        assertThat(anna.login(ANNA, PASSWORD)).isTrue();
        String tag = anna.json(anna.postJson("/api/labels",
                "{\"facet\":\"TAG\",\"name\":\"slot-it-" + UUID.randomUUID() + "\"}")).path("id").asText();
        List<JsonNode> documents = new ArrayList<>();
        for (int i = 0; i <= QUEUED; i++) {
            ApiClient.Response upload = anna.upload("/api/documents", "slot-" + i + ".pdf",
                    ("%PDF-1.4\n% slot " + i + " " + UUID.randomUUID() + "\n").getBytes(StandardCharsets.US_ASCII),
                    List.of());
            assertThat(upload.status()).as(upload.body()).isEqualTo(201);
            documents.add(anna.json(upload).path("document"));
        }
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        model.answer(held(entered, release, json(Map.of("labelId", tag))));
        model.otherwise(request -> {
            pause(CALL);
            return json(Map.of("labelId", tag));
        });
        try {
            // The first run holds the slot until every other run was requested and found it taken.
            review(documents.getFirst());
            assertThat(entered.await(20, TimeUnit.SECONDS)).as("first run reached the model").isTrue();
            List<UUID> queued = new ArrayList<>();
            for (JsonNode document : documents.subList(1, documents.size())) {
                queued.add(review(document));
            }
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(deferredBySlotWait(queued)).as("jobs deferred by the slot wait").isEqualTo(QUEUED));

            Instant released = Instant.now();
            release.countDown();

            // Before the hand-over, the k-th queued run started k slot waits (15s each) after the release.
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(states(queued)).containsOnly("SUCCEEDED"));
            List<Started> runs = queued.stream().map(this::started).toList();
            assertThat(runs).extracting(Started::attempt).containsOnly(1);
            assertThat(runs).extracting(Started::startedAt).as("oldest request first").isSorted();
            assertThat(Duration.between(released, runs.getFirst().startedAt())).isLessThan(Duration.ofSeconds(3));
            for (int i = 1; i < runs.size(); i++) {
                assertThat(Duration.between(runs.get(i - 1).finishedAt(), runs.get(i).startedAt()))
                        .as("queued run %d starts right after run %d ended", i, i - 1)
                        .isLessThan(Duration.ofSeconds(3));
            }
        } finally {
            release.countDown();
        }
    }

    private UUID review(JsonNode document) {
        String id = document.path("id").asText();
        long version = document.path("classification").path("version").asLong();
        Actor actor = new Actor(members.findCredentials(ANNA).orElseThrow().id());
        return requests.request(actor, new RunRequest("DOCUMENT_REVIEW", "slot-it:" + id + ":" + version,
                List.of(new SourceVersion("document", id, version)), Map.of(), false)).id();
    }

    /** Jobs of {@code runs} that are waiting, not executing, and due only after most of a slot wait. */
    private long deferredBySlotWait(List<UUID> runs) {
        return jdbc.sql("""
                SELECT count(*) FROM scheduled_tasks
                 WHERE task_name = 'ai-run' AND task_instance IN (:ids) AND NOT picked
                   AND execution_time > now() + interval '10 seconds'""")
                .param("ids", runs.stream().map(UUID::toString).toList()).query(Long.class).single();
    }

    private List<String> states(List<UUID> runs) {
        return jdbc.sql("SELECT state FROM ai_runs WHERE id IN (:ids)").param("ids", runs).query(String.class)
                .list();
    }

    private Started started(UUID run) {
        return jdbc.sql("SELECT attempt_started_at, finished_at, attempt FROM ai_runs WHERE id = ?").param(run)
                .query((rs, row) -> new Started(instant(rs.getTimestamp(1)), instant(rs.getTimestamp(2)),
                        rs.getInt(3)))
                .single();
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
