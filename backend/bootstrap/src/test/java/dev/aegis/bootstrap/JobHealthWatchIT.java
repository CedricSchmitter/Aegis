package dev.aegis.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import dev.aegis.bootstrap.support.ApiClient;
import dev.aegis.bootstrap.support.PostgresIT;
import dev.aegis.identity.application.api.MemberDirectory;
import dev.aegis.notifications.application.api.PublishNotice;
import dev.aegis.operations.adapters.jobs.JobHealthWatchTask;
import dev.aegis.operations.application.JobHealthWatchService;
import dev.aegis.operations.application.api.JobHealth;
import dev.aegis.operations.application.api.JobHealthWatch.Report;
import dev.aegis.operations.application.api.RecurringJob;
import dev.aegis.operations.application.ports.JobIncidentStore;
import dev.aegis.operations.application.ports.JobIncidentStore.JobIncident;
import dev.aegis.operations.application.ports.RecurringJobStateSource;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * Notices about stale recurring jobs (issue #94, ADR-0014) against PostgreSQL: the incident store keeps one open
 * incident per job under concurrent opening, and the watch over stored scheduler rows notifies both members once per
 * incident through the real notice inbox. The jobs, incidents and notices this test evaluates are its own.
 */
class JobHealthWatchIT extends PostgresIT {
    @LocalServerPort
    int port;

    @Autowired
    List<RecurringTask<?>> recurringTasks;

    @Autowired
    RecurringJobStateSource jobStates;

    @Autowired
    JobIncidentStore incidents;

    @Autowired
    MemberDirectory members;

    @Autowired
    PublishNotice notices;

    @Autowired
    JdbcClient jdbc;

    @Test
    void theWatchIsARecurringJobOfItsOwn() {
        assertThat(recurringTasks).extracting(RecurringTask::getName).contains(JobHealthWatchTask.NAME);
    }

    @Test
    void concurrentOpeningKeepsOneOpenIncidentAndAResolvedIncidentIsNotReopened() throws Exception {
        String job = "it-watch-" + System.nanoTime();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        try {
            ExecutorService pool = Executors.newFixedThreadPool(4);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<JobIncident>> opened = new ArrayList<>();
            Callable<JobIncident> open = () -> {
                start.await();
                return incidents.openIfAbsent(job, JobHealth.STALE, now.minus(Duration.ofDays(3)), now);
            };
            for (int i = 0; i < 8; i++) {
                opened.add(pool.submit(open));
            }
            start.countDown();
            Set<UUID> ids = opened.stream().map(JobHealthWatchIT::await).map(JobIncident::id)
                    .collect(Collectors.toSet());
            pool.shutdown();

            assertThat(ids).hasSize(1);
            JobIncident incident = incidents.findOpen(job).orElseThrow();
            assertThat(incident.id()).isEqualTo(ids.iterator().next());
            assertThat(incident.lastSuccessAt()).isEqualTo(now.minus(Duration.ofDays(3)));

            incidents.resolve(incident.id(), now.plusSeconds(60));
            assertThat(incidents.findOpen(job)).isEmpty();
            JobIncident next = incidents.openIfAbsent(job, JobHealth.NOT_SCHEDULED, null, now.plusSeconds(120));
            assertThat(next.id()).isNotEqualTo(incident.id());
            assertThat(next.lastSuccessAt()).isNull();
        } finally {
            jdbc.sql("DELETE FROM job_health_incidents WHERE job_name = ?").param(job).update();
        }
    }

    @Test
    void aStaleJobNotifiesBothMembersOnceUntilItRecovers() {
        String job = "it-watch-" + System.nanoTime();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant lastSuccess = now.minus(Duration.ofHours(1));
        try {
            insert(job, lastSuccess, now.minus(Duration.ofMinutes(1)), 7);
            JobHealthWatchService watch = watchStartedAnHourBefore(now, job);

            assertThat(watch.check()).isEqualTo(new Report(1, 0, List.of()));
            assertThat(watch.check()).isEqualTo(new Report(0, 0, List.of()));

            for (String member : List.of(ANNA, BEN)) {
                ApiClient client = login(member);
                List<JsonNode> own = noticesAbout(client, job);
                assertThat(own).hasSize(1);
                JsonNode notice = own.get(0);
                assertThat(notice.path("kind").asText()).isEqualTo("JOB_STALE");
                assertThat(notice.path("linkType").asText()).isEqualTo("STATUS");
                assertThat(notice.path("params").path("state").asText()).isEqualTo(JobHealth.STALE);
                assertThat(Instant.parse(notice.path("params").path("lastSuccessAt").asText())).isEqualTo(lastSuccess);
                assertThat(notice.path("params").propertyNames()).containsExactlyInAnyOrder("job", "state",
                        "lastSuccessAt");
            }

            jdbc.sql("UPDATE scheduled_tasks SET last_success = ?, consecutive_failures = 0 WHERE task_name = ?")
                    .params(Timestamp.from(now), job).update();
            assertThat(watch.check()).isEqualTo(new Report(0, 1, List.of()));
            assertThat(incidents.findOpen(job)).isEmpty();
            assertThat(jdbc.sql("SELECT count(*) FROM notices WHERE link_id = ?").param(job).query(Long.class)
                    .single()).isEqualTo(2L);
        } finally {
            jdbc.sql("DELETE FROM scheduled_tasks WHERE task_name = ?").param(job).update();
            jdbc.sql("DELETE FROM job_health_incidents WHERE job_name = ?").param(job).update();
            jdbc.sql("DELETE FROM notices WHERE kind = 'JOB_STALE' AND link_id = ?").param(job).update();
        }
    }

    /** A watch over the one test job that started an hour before {@code now}, so its start grace has passed. */
    private JobHealthWatchService watchStartedAnHourBefore(Instant now, String job) {
        MutableClock clock = new MutableClock(now.minus(Duration.ofHours(1)));
        JobHealthWatchService watch = new JobHealthWatchService(List.of(new RecurringJob(job, Duration.ofMinutes(5))),
                jobStates, incidents, members, notices, clock);
        clock.now = now;
        return watch;
    }

    private static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private List<JsonNode> noticesAbout(ApiClient client, String job) {
        return StreamSupport.stream(client.json(client.get("/api/notices")).spliterator(), false)
                .filter(notice -> job.equals(notice.path("linkId").asText())).toList();
    }

    private void insert(String name, Instant lastSuccess, Instant lastFailure, int failures) {
        jdbc.sql("""
                INSERT INTO scheduled_tasks (task_name, task_instance, task_data, execution_time, picked, last_success,
                    last_failure, consecutive_failures, version)
                VALUES (?, 'recurring', ?, ?, false, ?, ?, ?, 1)
                """).params(name, new byte[] {1}, Timestamp.from(Instant.now().plus(Duration.ofDays(30))),
                Timestamp.from(lastSuccess), Timestamp.from(lastFailure), failures).update();
    }

    private static JobIncident await(Future<JobIncident> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ApiClient login(String username) {
        ApiClient client = new ApiClient(port);
        assertThat(client.login(username, PASSWORD)).isTrue();
        return client;
    }
}
