package dev.aegis.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import dev.aegis.bootstrap.support.ApiClient;
import dev.aegis.bootstrap.support.PostgresIT;
import dev.aegis.operations.application.IntegrationStatusService;
import dev.aegis.operations.application.api.JobHealth;
import dev.aegis.operations.application.api.RecurringJob;
import dev.aegis.operations.application.ports.RecurringJobStateSource;
import dev.aegis.review.adapters.jobs.ReviewReconciliationTask;
import dev.aegis.shared.Actor;
import dev.aegis.shared.MemberId;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * Recurring job health on the status page (issue #92, ADR-0013) against PostgreSQL: every recurring db-scheduler
 * job is reported with its interval, a job run through the scheduler shows its success, and the scheduler adapter
 * turns stored rows into healthy, failing and stale jobs. The rows this test evaluates are its own, because other
 * test classes may truncate scheduled_tasks (RestoreIT).
 */
class RecurringJobStatusIT extends PostgresIT {
    @LocalServerPort
    int port;

    @Autowired
    List<RecurringTask<?>> recurringTasks;

    @Autowired
    SchedulerClient scheduler;

    @Autowired
    @Qualifier("reconcileReviewInboxJob")
    RecurringTask<Void> reviewJob;

    @Autowired
    RecurringJobStateSource jobStates;

    @Autowired
    JdbcClient jdbc;

    @Test
    void theStatusPageReportsEveryRecurringJobWithItsIntervalAndItsLastSuccess() {
        ApiClient anonymous = new ApiClient(port);
        assertThat(anonymous.get("/api/status").status()).isEqualTo(401);
        ApiClient anna = login();

        JsonNode jobs = anna.json(anna.get("/api/status")).path("jobs");
        Set<String> registered = recurringTasks.stream().map(RecurringTask::getName).collect(Collectors.toSet());
        assertThat(StreamSupport.stream(jobs.spliterator(), false).map(job -> job.path("name").asText()))
                .containsExactlyInAnyOrderElementsOf(registered);
        assertThat(intervals(jobs)).containsEntry("reconcile-review-inbox", 12 * 3600L)
                .containsEntry("check-archive-text", 60L).containsEntry("reconcile-documents", 300L)
                .containsEntry("watch-job-health", 12 * 3600L);
        // Names, states and times only: no job data reaches the page.
        jobs.forEach(job -> assertThat(job.propertyNames()).containsExactlyInAnyOrder("name", "state",
                "intervalSeconds", "lastSuccessAt", "lastFailureAt", "consecutiveFailures", "nextRunAt"));

        Instant before = runReviewJobAndAwaitSuccess();

        JsonNode review = job(anna.json(anna.get("/api/status")).path("jobs"), ReviewReconciliationTask.NAME);
        assertThat(review.path("state").asText()).isEqualTo(JobHealth.OK);
        assertThat(Instant.parse(review.path("lastSuccessAt").asText())).isAfter(before);
        assertThat(Instant.parse(review.path("nextRunAt").asText())).isAfter(Instant.now().plus(Duration.ofHours(1)));
    }

    @Test
    void theSchedulerAdapterTurnsStoredRowsIntoHealthyFailingAndStaleJobs() {
        String prefix = "it-job-health-" + System.nanoTime() + "-";
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant later = now.plus(Duration.ofDays(30));
        try {
            insert(prefix + "healthy", "recurring", now.minus(Duration.ofHours(3)), null, 0, later);
            insert(prefix + "failing", "recurring", now.minus(Duration.ofDays(1)), now.minus(Duration.ofMinutes(2)), 1,
                    later);
            insert(prefix + "stale", "recurring", now.minus(Duration.ofDays(3)), now.minus(Duration.ofHours(1)), 3,
                    later);
            // A one-time instance of the same name is not the recurring job's schedule.
            insert(prefix + "healthy", "one-time", null, now, 7, later);

            Map<String, RecurringJobStateSource.RecurringJobState> states = jobStates.states(
                    List.of(prefix + "healthy", prefix + "failing", prefix + "stale", prefix + "missing"));
            assertThat(states).containsOnlyKeys(prefix + "healthy", prefix + "failing", prefix + "stale");
            assertThat(states.get(prefix + "failing")).isEqualTo(new RecurringJobStateSource.RecurringJobState(
                    now.minus(Duration.ofDays(1)), now.minus(Duration.ofMinutes(2)), 1, later));

            List<RecurringJob> declared = List.of(new RecurringJob(prefix + "healthy", Duration.ofDays(1)),
                    new RecurringJob(prefix + "failing", Duration.ofDays(1)),
                    new RecurringJob(prefix + "stale", Duration.ofDays(1)),
                    new RecurringJob(prefix + "missing", Duration.ofDays(1)));
            IntegrationStatusService status = new IntegrationStatusService(List.of(), actor -> Map.of(), declared,
                    jobStates, actor -> {
                    }, Clock.fixed(now, ZoneOffset.UTC));

            assertThat(status.status(new Actor(MemberId.random())).jobs())
                    .extracting(JobHealth::name, JobHealth::state, JobHealth::consecutiveFailures)
                    .containsExactly(tuple(prefix + "healthy", JobHealth.OK, 0),
                            tuple(prefix + "failing", JobHealth.FAILING, 1),
                            tuple(prefix + "stale", JobHealth.STALE, 3),
                            tuple(prefix + "missing", JobHealth.NOT_SCHEDULED, 0));
        } finally {
            jdbc.sql("DELETE FROM scheduled_tasks WHERE task_name LIKE ?").param(prefix + "%").update();
        }
    }

    /** Triggers the review job through db-scheduler and waits until that execution completed successfully. */
    private Instant runReviewJobAndAwaitSuccess() {
        TaskInstanceId id = TaskInstanceId.of(ReviewReconciliationTask.NAME, RecurringTask.INSTANCE);
        Instant before = reviewLastSuccess().orElse(Instant.EPOCH);
        Instant now = Instant.now();
        // The row is missing when an earlier test class truncated scheduled_tasks (RestoreIT).
        if (!scheduler.scheduleIfNotExists(reviewJob.instance(RecurringTask.INSTANCE), now)) {
            scheduler.reschedule(id, now);
        }
        await().atMost(Duration.ofSeconds(30)).untilAsserted(
                () -> assertThat(reviewLastSuccess()).hasValueSatisfying(t -> assertThat(t).isAfter(before)));
        return before;
    }

    private Optional<Instant> reviewLastSuccess() {
        return jdbc.sql("SELECT last_success FROM scheduled_tasks WHERE task_name = ? AND task_instance = ?")
                .params(ReviewReconciliationTask.NAME, RecurringTask.INSTANCE).query(Timestamp.class).optional()
                .map(Timestamp::toInstant);
    }

    private void insert(String name, String instance, Instant lastSuccess, Instant lastFailure, int failures,
            Instant executionTime) {
        jdbc.sql("""
                INSERT INTO scheduled_tasks (task_name, task_instance, task_data, execution_time, picked, last_success,
                    last_failure, consecutive_failures, version)
                VALUES (?, ?, ?, ?, false, ?, ?, ?, 1)
                """).params(name, instance, new byte[] {1, 2, 3}, Timestamp.from(executionTime), timestamp(lastSuccess),
                timestamp(lastFailure), failures).update();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Map<String, Long> intervals(JsonNode jobs) {
        return StreamSupport.stream(jobs.spliterator(), false).collect(Collectors.toMap(
                job -> job.path("name").asText(), job -> job.path("intervalSeconds").asLong()));
    }

    private static JsonNode job(JsonNode jobs, String name) {
        return StreamSupport.stream(jobs.spliterator(), false).filter(job -> job.path("name").asText().equals(name))
                .findFirst().orElseThrow();
    }

    private ApiClient login() {
        ApiClient client = new ApiClient(port);
        assertThat(client.login(ANNA, PASSWORD)).isTrue();
        return client;
    }
}
