package dev.aegis.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import dev.aegis.bootstrap.support.ApiClient;
import dev.aegis.bootstrap.support.FakePaperlessArchive;
import dev.aegis.bootstrap.support.PostgresIT;
import dev.aegis.documents.application.api.DocumentCommands;
import dev.aegis.documents.application.api.DocumentQueries;
import dev.aegis.documents.application.api.DocumentSearchRequest;
import dev.aegis.identity.application.api.MemberCredentialsQuery;
import dev.aegis.identity.application.api.MemberDirectory;
import dev.aegis.identity.application.api.MemberSummary;
import dev.aegis.review.adapters.jobs.ReviewReconciliationTask;
import dev.aegis.review.application.api.ReviewInbox;
import dev.aegis.review.application.api.ReviewReconciliation;
import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.NotAuthorizedException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Background reconciliation of the review inbox (issue #89, ADR-0009) against PostgreSQL: the recurring db-scheduler
 * job creates a card and one notice per active member without anyone opening the inbox, runs as the review
 * reconciler that can neither decide cards nor change records, creates each card and its notices exactly once when
 * it races with members opening the inbox, and commits a card together with its notices.
 */
class ReviewReconciliationIT extends PostgresIT {
    private static final Actor RECONCILER = ReviewReconciliation.RECONCILER;

    @LocalServerPort
    int port;

    @Autowired
    FakePaperlessArchive archive;

    @Autowired
    ReviewReconciliation reconciliation;

    @Autowired
    ReviewInbox inbox;

    @Autowired
    DocumentQueries documents;

    @Autowired
    DocumentCommands documentCommands;

    @Autowired
    MemberCredentialsQuery credentials;

    @Autowired
    MemberDirectory directory;

    @Autowired
    SchedulerClient scheduler;

    @Autowired
    @Qualifier("reconcileReviewInboxJob")
    RecurringTask<Void> job;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void theRecurringJobCreatesTheCardAndNotifiesEveryActiveMemberWithoutAnInboxVisit() {
        ApiClient anna = login(ANNA);
        ApiClient ben = login(BEN);
        String documentId = uploadWithoutText(anna, "job");
        assertThat(cardFor(documentId)).isEmpty();

        runJobAndAwaitCompletion();

        UUID card = cardFor(documentId).orElseThrow();
        String title = anna.json(anna.get("/api/documents/" + documentId)).path("title").asText();
        for (ApiClient member : List.of(anna, ben)) {
            assertThat(member.json(member.get("/api/notices?unreadOnly=true"))).anySatisfy(notice -> {
                assertThat(notice.path("kind").asText()).isEqualTo("REVIEW_ITEM");
                assertThat(notice.path("linkType").asText()).isEqualTo("REVIEW");
                assertThat(notice.path("linkId").asText()).isEqualTo(card.toString());
                // The notice names the card (issue #91): its key and the document title, never translated text.
                assertThat(notice.path("params").path("titleKey").asText()).isEqualTo("review.noText");
                assertThat(notice.path("params").path("title").asText()).isEqualTo(title);
            });
        }
        assertExactlyOneNoticePerActiveMember(card);

        // The schedule lives in PostgreSQL: after the run the next one waits there, so a restart continues it.
        Instant next = jdbc.sql("SELECT execution_time FROM scheduled_tasks WHERE task_name = ? AND task_instance = ?")
                .params(ReviewReconciliationTask.NAME, RecurringTask.INSTANCE).query(Timestamp.class).single()
                .toInstant();
        assertThat(next).isAfter(Instant.now().plus(Duration.ofHours(1)));

        // Running again, as after a restart, and a member opening the inbox create nothing twice.
        runJobAndAwaitCompletion();
        assertThat(anna.get("/api/reviews").status()).isEqualTo(200);
        assertThat(cardsFor(documentId)).isEqualTo(1);
        assertExactlyOneNoticePerActiveMember(card);
    }

    @Test
    void theReconcilerReadsTheProducersButCannotDecideCardsOrChangeRecords() {
        ApiClient anna = login(ANNA);
        String documentId = uploadWithoutText(anna, "identity");
        DocumentId id = DocumentId.of(documentId);

        assertThat(reconciliation.reconcile(RECONCILER).created()).isPositive();
        UUID card = cardFor(documentId).orElseThrow();
        long version = anna.json(anna.get("/api/documents/" + documentId)).path("version").asLong();

        assertThat(documents.search(RECONCILER, DocumentSearchRequest.text("")).matchingDocuments()).isPositive();
        assertThatThrownBy(() -> inbox.dismiss(RECONCILER, card, 0)).isInstanceOf(NotAuthorizedException.class);
        assertThatThrownBy(() -> inbox.act(RECONCILER, card, "RETRY_ARCHIVAL", 0))
                .isInstanceOf(NotAuthorizedException.class);
        assertThatThrownBy(() -> inbox.list(RECONCILER, null, null)).isInstanceOf(NotAuthorizedException.class);
        assertThatThrownBy(() -> documentCommands.moveToTrash(RECONCILER, id, version))
                .isInstanceOf(NotAuthorizedException.class);
        assertThatThrownBy(() -> documents.get(RECONCILER, id)).isInstanceOf(NotAuthorizedException.class);
        assertThatThrownBy(() -> reconciliation.reconcile(Actor.householdProcess("someone-else")))
                .isInstanceOf(NotAuthorizedException.class);

        assertThat(jdbc.sql("SELECT status FROM review_items WHERE id = ?").param(card).query(String.class).single())
                .isEqualTo("OPEN");
        assertThat(anna.json(anna.get("/api/documents/" + documentId)).path("trash").isNull()).isTrue();
    }

    @Test
    void concurrentReconciliationsByTheJobAndMembersCreateEachCardAndItsNoticesExactlyOnce() throws Exception {
        ApiClient anna = login(ANNA);
        List<String> sources = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            sources.add(uploadWithoutText(anna, "race-" + i));
        }
        List<ApiClient> visitors = List.of(login(ANNA), login(BEN), login(ANNA), login(BEN));
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Integer>> runs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            runs.add(() -> {
                start.await();
                return reconciliation.reconcile(RECONCILER).created();
            });
            ApiClient visitor = visitors.get(i);
            runs.add(() -> {
                start.await();
                ApiClient.Response opened = visitor.get("/api/reviews");
                assertThat(opened.status()).as(opened.body()).isEqualTo(200);
                return 0;
            });
        }
        ExecutorService pool = Executors.newFixedThreadPool(runs.size());
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> run : runs) {
                futures.add(pool.submit(run));
            }
            start.countDown();
            for (Future<Integer> future : futures) {
                // Every run completes: none fails on a card another run inserted.
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        for (String source : sources) {
            assertThat(cardsFor(source)).as("cards for %s", source).isEqualTo(1);
            assertExactlyOneNoticePerActiveMember(cardFor(source).orElseThrow());
        }
    }

    @Test
    void aCardAndItsNoticesCommitTogetherSoARunThatFailsLeavesNothingBehind() {
        ApiClient anna = login(ANNA);
        String documentId = uploadWithoutText(anna, "rollback");
        List<UUID> seenInside = new ArrayList<>();

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            assertThat(reconciliation.reconcile(RECONCILER).created()).isPositive();
            UUID card = cardFor(documentId).orElseThrow();
            assertThat(noticesFor(card)).isNotEmpty();
            seenInside.add(card);
            throw new IllegalStateException("the process stops before the run commits");
        })).isInstanceOf(IllegalStateException.class);

        UUID card = seenInside.getFirst();
        assertThat(cardFor(documentId)).isEmpty();
        assertThat(noticesFor(card)).isEmpty();

        assertThat(reconciliation.reconcile(RECONCILER).created()).isPositive();
        assertThat(cardFor(documentId)).contains(card);
        assertExactlyOneNoticePerActiveMember(card);
    }

    /** Triggers the recurring task through db-scheduler and waits until that execution completed successfully. */
    private void runJobAndAwaitCompletion() {
        TaskInstanceId id = TaskInstanceId.of(ReviewReconciliationTask.NAME, RecurringTask.INSTANCE);
        Instant before = lastSuccess().orElse(Instant.EPOCH);
        Instant now = Instant.now();
        // The row is missing when an earlier test class truncated scheduled_tasks (RestoreIT).
        if (!scheduler.scheduleIfNotExists(job.instance(RecurringTask.INSTANCE), now)) {
            scheduler.reschedule(id, now);
        }
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(lastSuccess()).hasValueSatisfying(t -> assertThat(t).isAfter(before)));
    }

    private Optional<Instant> lastSuccess() {
        return jdbc.sql("SELECT last_success FROM scheduled_tasks WHERE task_name = ? AND task_instance = ?")
                .params(ReviewReconciliationTask.NAME, RecurringTask.INSTANCE).query(Timestamp.class).optional()
                .map(Timestamp::toInstant);
    }

    private Optional<UUID> cardFor(String documentId) {
        return jdbc.sql("SELECT id FROM review_items WHERE source_id = ? AND reason = 'NO_TEXT_RECOGNIZED'")
                .param(documentId).query(UUID.class).optional();
    }

    private int cardsFor(String documentId) {
        return jdbc.sql("SELECT count(*) FROM review_items WHERE source_id = ? AND reason = 'NO_TEXT_RECOGNIZED'")
                .param(documentId).query(Integer.class).single();
    }

    private Map<UUID, Integer> noticesFor(UUID card) {
        return jdbc.sql("SELECT recipient_id, count(*) AS n FROM notices WHERE dedup_key = ? GROUP BY recipient_id")
                .param("review:" + card).query((rs, row) -> Map.entry(rs.getObject("recipient_id", UUID.class),
                        rs.getInt("n")))
                .list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private void assertExactlyOneNoticePerActiveMember(UUID card) {
        Actor anna = new Actor(credentials.findCredentials(ANNA).orElseThrow().id());
        Set<UUID> active = directory.listMembers(anna).stream().filter(MemberSummary::active).map(MemberSummary::id)
                .map(MemberId::value).collect(Collectors.toSet());
        Map<UUID, Integer> notices = noticesFor(card);
        assertThat(notices.keySet()).isEqualTo(active);
        assertThat(notices.values()).containsOnly(1);
    }

    private ApiClient login(String username) {
        ApiClient client = new ApiClient(port);
        assertThat(client.login(username, PASSWORD)).isTrue();
        return client;
    }

    /** Uploads a photo in which the archive recognizes no text and waits until that was measured (ADR-0012). */
    private String uploadWithoutText(ApiClient client, String label) {
        byte[] photo = uniqueJpeg(label);
        archive.ocrText(photo, " ");
        ApiClient.Response uploaded = client.upload("/api/documents", "IMG_" + System.nanoTime() + ".jpg", photo,
                List.of("HOUSEHOLD"));
        assertThat(uploaded.status()).as(uploaded.body()).isEqualTo(201);
        String id = client.json(uploaded).path("document").path("id").asText();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(client.json(client.get(
                "/api/documents/" + id)).path("textRecognition").path("state").asText()).isEqualTo("NONE"));
        return id;
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
