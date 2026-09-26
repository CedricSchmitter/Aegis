package dev.aegis.operations.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.operations.application.api.BackupHealth;
import dev.aegis.operations.application.api.DiskSpace;
import dev.aegis.operations.application.api.HealthCheck;
import dev.aegis.operations.application.api.JobHealth;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StatusOverviewTest {
    private static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");
    private static final Instant EARLIER = NOW.minus(Duration.ofHours(9));

    @Test
    void aWorkingOrRunningBackupIsOk() {
        assertThat(StatusOverview.backup(backup(BackupHealth.OK, null)))
                .isEqualTo(HealthCheck.ok("backup", "backup.ok", EARLIER));
        assertThat(StatusOverview.backup(backup(BackupHealth.RUNNING, null)))
                .isEqualTo(HealthCheck.ok("backup", "backup.running", EARLIER));
    }

    @Test
    void everyOtherBackupStateIsAProblemWithAStableReasonAndNoFreeText() {
        HealthCheck failed = StatusOverview.backup(backup(BackupHealth.FAILED, "destination.notMounted"));
        assertThat(failed.state()).isEqualTo(HealthCheck.PROBLEM);
        assertThat(failed.reason()).isEqualTo("backup.failed");
        assertThat(failed.params()).isEqualTo(Map.of("failureCode", "destination.notMounted"));
        assertThat(failed.lastSuccessAt()).isEqualTo(EARLIER);

        assertThat(StatusOverview.backup(backup(BackupHealth.STALE, null)).reason()).isEqualTo("backup.stale");
        assertThat(StatusOverview.backup(backup(BackupHealth.INTERRUPTED, null)).reason())
                .isEqualTo("backup.interrupted");
        HealthCheck unknown = StatusOverview.backup(backup(BackupHealth.UNKNOWN, BackupHealth.STATE_UNREADABLE));
        assertThat(unknown.reason()).isEqualTo("backup.unknown");
        assertThat(unknown.params()).isEqualTo(Map.of("failureCode", "state.unreadable"));
    }

    @Test
    void aMissingBackupIsListedAsAProblemAndNotHiddenLikeAnOptionalIntegration() {
        HealthCheck missing = StatusOverview.backup(new BackupHealth(BackupHealth.NOT_CONFIGURED, null, null, null,
                null));

        assertThat(missing.state()).isEqualTo(HealthCheck.PROBLEM);
        assertThat(missing.reason()).isEqualTo("backup.notConfigured");
        assertThat(missing.params()).isEmpty();
    }

    @Test
    void storageNamesTheLowDisksFirstAndThenTheUnmeasuredOnes() {
        DiskSpace dataOk = disk(DiskSpace.DATA, DiskSpace.OK);
        assertThat(StatusOverview.storage(List.of(dataOk))).isEqualTo(HealthCheck.ok("storage", "storage.ok", null));

        HealthCheck low = StatusOverview.storage(List.of(disk(DiskSpace.DATA, DiskSpace.LOW),
                disk(DiskSpace.BACKUP, DiskSpace.LOW)));
        assertThat(low.state()).isEqualTo(HealthCheck.PROBLEM);
        assertThat(low.reason()).isEqualTo("storage.low");
        assertThat(low.params()).isEqualTo(Map.of("disks", "data,backup"));

        HealthCheck lowBeatsUnknown = StatusOverview.storage(List.of(DiskSpace.unknown(DiskSpace.DATA, NOW),
                disk(DiskSpace.BACKUP, DiskSpace.LOW)));
        assertThat(lowBeatsUnknown.params()).isEqualTo(Map.of("disks", "backup"));

        HealthCheck unmeasured = StatusOverview.storage(List.of(DiskSpace.unknown(DiskSpace.DATA, NOW)));
        assertThat(unmeasured.reason()).isEqualTo("storage.unmeasured");
        assertThat(unmeasured.params()).isEqualTo(Map.of("disks", "data"));
    }

    @Test
    void jobsAreListedOnlyWhileOneDoesNotRunAsPlanned() {
        JobHealth ok = job("check-archive-text", JobHealth.OK);
        JobHealth pending = job("watch-job-health", JobHealth.PENDING);
        assertThat(StatusOverview.jobs(List.of(ok, pending))).isEmpty();

        HealthCheck attention = StatusOverview.jobs(List.of(ok, job("reconcile-documents", JobHealth.STALE),
                job("reconcile-review-inbox", JobHealth.FAILING), pending)).orElseThrow();
        assertThat(attention.area()).isEqualTo("jobs");
        assertThat(attention.state()).isEqualTo(HealthCheck.PROBLEM);
        assertThat(attention.reason()).isEqualTo("jobs.notRunning");
        assertThat(attention.params()).isEqualTo(Map.of("jobs", "reconcile-documents,reconcile-review-inbox"));

        for (String state : List.of(JobHealth.NOT_SCHEDULED, JobHealth.UNKNOWN)) {
            assertThat(StatusOverview.jobs(List.of(job("check-archive-text", state)))).as(state).isPresent();
        }
    }

    private static BackupHealth backup(String state, String failureCode) {
        return new BackupHealth(state, failureCode, EARLIER, NOW, null);
    }

    private static DiskSpace disk(String name, String state) {
        return new DiskSpace(name, state, 100L, DiskSpace.LOW.equals(state) ? 1L : 90L, 10L, NOW);
    }

    private static JobHealth job(String name, String state) {
        return new JobHealth(name, state, Duration.ofMinutes(5), null, null, 0, null);
    }
}
