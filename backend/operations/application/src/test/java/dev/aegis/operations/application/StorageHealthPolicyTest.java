package dev.aegis.operations.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.operations.application.api.BackupHealth;
import dev.aegis.operations.application.api.DiskSpace;
import dev.aegis.operations.application.ports.BackupStateSource.BackupState;
import dev.aegis.operations.application.ports.DiskUsage;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StorageHealthPolicyTest {
    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");
    private static final long GB = 1_000_000_000L;
    private static final StorageLimits LIMITS = new StorageLimits(0.10, 5 * GB);

    @Test
    void aRecentVerifiedSuccessIsOkAndOneMissingDailyRunMakesItStale() {
        assertThat(backup(state(NOW.minus(Duration.ofHours(9)), null, null)).state()).isEqualTo(BackupHealth.OK);
        assertThat(backup(state(NOW.minus(Duration.ofHours(26)), null, null)).state()).isEqualTo(BackupHealth.OK);

        BackupHealth stale = backup(state(NOW.minus(Duration.ofHours(26)).minusSeconds(1), null, null));
        assertThat(stale.state()).isEqualTo(BackupHealth.STALE);
        assertThat(stale.failureCode()).isNull();
        assertThat(stale.lastSuccessAt()).isEqualTo(NOW.minus(Duration.ofHours(26)).minusSeconds(1));
    }

    @Test
    void aFailedAttemptNamesItsCodeAndKeepsTheLastSuccess() {
        Instant success = NOW.minus(Duration.ofHours(33));
        BackupHealth failed = backup(state(success, null, "destination.notMounted"));

        assertThat(failed.state()).isEqualTo(BackupHealth.FAILED);
        assertThat(failed.failureCode()).isEqualTo("destination.notMounted");
        assertThat(failed.lastSuccessAt()).isEqualTo(success);
    }

    @Test
    void aRunningBackupIsNeitherFailureNorSuccessUntilItIsClearlyOverdue() {
        BackupState running = state(NOW.minus(Duration.ofHours(30)), NOW.minus(Duration.ofMinutes(2)),
                "check.failed");
        BackupHealth health = backup(running);
        assertThat(health.state()).isEqualTo(BackupHealth.RUNNING);
        assertThat(health.failureCode()).as("an earlier failure does not describe the running backup").isNull();

        BackupState longRunning = state(NOW.minus(Duration.ofHours(30)), NOW.minus(Duration.ofHours(3)), null);
        assertThat(backup(longRunning).state()).isEqualTo(BackupHealth.RUNNING);

        BackupState powerLoss = state(NOW.minus(Duration.ofHours(30)), NOW.minus(Duration.ofHours(3)).minusSeconds(1),
                null);
        assertThat(backup(powerLoss).state()).isEqualTo(BackupHealth.INTERRUPTED);
    }

    @Test
    void withoutStateVerificationOrSuccessTheBackupIsNotConfigured() {
        assertThat(StorageHealthPolicy.backup(Optional.empty(), NOW).state()).isEqualTo(BackupHealth.NOT_CONFIGURED);
        assertThat(backup(new BackupState(false, NOW, NOW, null, null, null)).state())
                .isEqualTo(BackupHealth.NOT_CONFIGURED);
        assertThat(backup(new BackupState(true, null, NOW, null, null, null)).state())
                .isEqualTo(BackupHealth.NOT_CONFIGURED);
        assertThat(StorageHealthPolicy.unreadable())
                .isEqualTo(new BackupHealth(BackupHealth.UNKNOWN, "state.unreadable", null, null, null));
    }

    @Test
    void aDiskIsLowBelowTheHigherOfTheTwoLimits() {
        // 93 GB: 10 % is higher than 5 GB.
        DiskSpace vm = StorageHealthPolicy.disk("data", new DiskUsage(93 * GB, 9_300_000_000L, NOW), LIMITS);
        assertThat(vm.lowBelowBytes()).isEqualTo(9_300_000_000L);
        assertThat(vm.state()).isEqualTo(DiskSpace.OK);
        assertThat(StorageHealthPolicy.disk("data", new DiskUsage(93 * GB, 9_299_999_999L, NOW), LIMITS).state())
                .isEqualTo(DiskSpace.LOW);

        // 30 GB: 5 GB is higher than 10 %.
        DiskSpace small = StorageHealthPolicy.disk("data", new DiskUsage(30 * GB, 4 * GB, NOW), LIMITS);
        assertThat(small.lowBelowBytes()).isEqualTo(5 * GB);
        assertThat(small.state()).isEqualTo(DiskSpace.LOW);
        assertThat(small).extracting(DiskSpace::totalBytes, DiskSpace::freeBytes, DiskSpace::measuredAt)
                .containsExactly(30 * GB, 4 * GB, NOW);
    }

    @Test
    void aLowDiskRecoversOnlyWithAMarginAboveTheLimit() {
        DiskSpace justAbove = StorageHealthPolicy.disk("data", new DiskUsage(100 * GB, 10 * GB + 1, NOW), LIMITS);
        assertThat(justAbove.state()).isEqualTo(DiskSpace.OK);
        assertThat(StorageHealthPolicy.recovered(justAbove)).isFalse();

        DiskSpace recovered = StorageHealthPolicy.disk("data", new DiskUsage(100 * GB, 11 * GB, NOW), LIMITS);
        assertThat(StorageHealthPolicy.recovered(recovered)).isTrue();
        assertThat(StorageHealthPolicy.recovered(DiskSpace.unknown("data", NOW))).isFalse();
    }

    @Test
    void limitsMustBeMeaningful() {
        assertThatThrownBy(() -> new StorageLimits(0, GB)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StorageLimits(1, GB)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StorageLimits(0.1, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    private static BackupHealth backup(BackupState state) {
        return StorageHealthPolicy.backup(Optional.of(state), NOW);
    }

    private static BackupState state(Instant lastSuccess, Instant runningSince, String failureCode) {
        return new BackupState(true, lastSuccess, NOW.minus(Duration.ofHours(8)), runningSince, failureCode, null);
    }
}
