package dev.aegis.operations.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.operations.application.api.JobHealth;
import dev.aegis.operations.application.api.RecurringJob;
import dev.aegis.operations.application.ports.RecurringJobStateSource.RecurringJobState;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class JobHealthPolicyTest {
    private static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");
    private static final RecurringJob DAILY = new RecurringJob("reconcile-review-inbox", Duration.ofDays(1));
    private static final RecurringJob EVERY_MINUTE = new RecurringJob("check-archive-text", Duration.ofMinutes(1));

    @Test
    void aJobThatSucceededWithinItsIntervalIsHealthy() {
        JobHealth health = JobHealthPolicy.evaluate(DAILY,
                new RecurringJobState(NOW.minus(Duration.ofHours(3)), NOW.minus(Duration.ofDays(9)), 0,
                        NOW.plus(Duration.ofHours(21))), NOW);

        assertThat(health.state()).isEqualTo(JobHealth.OK);
        assertThat(health.lastSuccessAt()).isEqualTo(NOW.minus(Duration.ofHours(3)));
        assertThat(health.nextRunAt()).isEqualTo(NOW.plus(Duration.ofHours(21)));
        assertThat(health.interval()).isEqualTo(Duration.ofDays(1));
    }

    @Test
    void aFailedLatestRunIsFailingUntilTheRunAfterItFailsToo() {
        Instant success = NOW.minus(Duration.ofDays(1)).minus(Duration.ofMinutes(5));
        RecurringJobState failedOnce = new RecurringJobState(success, NOW.minus(Duration.ofMinutes(4)), 1,
                NOW.plus(Duration.ofDays(1)));

        JobHealth health = JobHealthPolicy.evaluate(DAILY, failedOnce, NOW);

        assertThat(health.state()).isEqualTo(JobHealth.FAILING);
        assertThat(health.lastFailureAt()).isEqualTo(NOW.minus(Duration.ofMinutes(4)));
        assertThat(health.consecutiveFailures()).isEqualTo(1);
        // Still failing just before the next run is overdue; stale once two intervals and the grace have passed.
        Instant beforeStale = success.plus(Duration.ofDays(2)).plus(JobHealthPolicy.GRACE);
        assertThat(JobHealthPolicy.evaluate(DAILY, failedOnce, beforeStale).state()).isEqualTo(JobHealth.FAILING);
        assertThat(JobHealthPolicy.evaluate(DAILY, failedOnce, beforeStale.plusSeconds(1)).state())
                .isEqualTo(JobHealth.STALE);
    }

    @Test
    void aJobWithoutASuccessForTwoIntervalsAndTheGraceIsStaleWhateverTheReason() {
        // Every run failed since, or the scheduler stopped picking the job up (no failure recorded).
        RecurringJobState failing = new RecurringJobState(NOW.minus(Duration.ofMinutes(13)), NOW.minusSeconds(20),
                12, NOW.plusSeconds(40));
        RecurringJobState notPickedUp = new RecurringJobState(NOW.minus(Duration.ofMinutes(13)), null, 0,
                NOW.minus(Duration.ofMinutes(12)));

        assertThat(JobHealthPolicy.evaluate(EVERY_MINUTE, failing, NOW).state()).isEqualTo(JobHealth.STALE);
        assertThat(JobHealthPolicy.evaluate(EVERY_MINUTE, notPickedUp, NOW).state()).isEqualTo(JobHealth.STALE);
        // Twelve minutes are the limit for a job that runs every minute: a restart during a deployment is not stale.
        assertThat(JobHealthPolicy.staleAfter(EVERY_MINUTE.interval())).isEqualTo(Duration.ofMinutes(12));
        assertThat(JobHealthPolicy.evaluate(EVERY_MINUTE,
                new RecurringJobState(NOW.minus(Duration.ofMinutes(6)), null, 0, NOW.minus(Duration.ofMinutes(5))),
                NOW).state()).isEqualTo(JobHealth.OK);
    }

    @Test
    void aJobThatNeverSucceededIsPendingUntilItsFirstRunIsOverdueOrFailedTwice() {
        Instant firstRun = NOW.plus(Duration.ofHours(2));
        assertThat(JobHealthPolicy.evaluate(DAILY, new RecurringJobState(null, null, 0, firstRun), NOW).state())
                .isEqualTo(JobHealth.PENDING);
        assertThat(JobHealthPolicy.evaluate(DAILY,
                new RecurringJobState(null, null, 0, NOW.minus(Duration.ofDays(1)).minus(Duration.ofMinutes(11))),
                NOW).state()).isEqualTo(JobHealth.STALE);
        assertThat(JobHealthPolicy.evaluate(DAILY, new RecurringJobState(null, NOW, 1, firstRun), NOW).state())
                .isEqualTo(JobHealth.FAILING);
        assertThat(JobHealthPolicy.evaluate(DAILY, new RecurringJobState(null, NOW, 2, firstRun), NOW).state())
                .isEqualTo(JobHealth.STALE);
    }

    @Test
    void aJobWithoutAStoredScheduleIsNotScheduled() {
        JobHealth health = JobHealthPolicy.evaluate(DAILY, null, NOW);

        assertThat(health.state()).isEqualTo(JobHealth.NOT_SCHEDULED);
        assertThat(health.lastSuccessAt()).isNull();
        assertThat(health.nextRunAt()).isNull();
    }
}
