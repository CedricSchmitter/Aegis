package dev.aegis.operations.application;

import dev.aegis.operations.application.api.BackupHealth;
import dev.aegis.operations.application.api.DiskSpace;
import dev.aegis.operations.application.ports.BackupStateSource.BackupState;
import dev.aegis.operations.application.ports.DiskUsage;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Judges the external backup and the free disk space (ADR-0015).
 *
 * <p>The backup runs daily at 03:30; a success older than {@link #STALE_AFTER} means at least one daily run is
 * missing. A run is at most two hours long (the systemd unit's start timeout), so a run that has been marked as
 * running for longer than {@link #INTERRUPTED_AFTER} ended without recording its outcome, for example because the VM
 * lost power. A running backup is neither a failure nor a success.
 *
 * <p>A disk is low below {@link #lowBelow}. Once low, it only counts as recovered when its free space exceeds that
 * limit by {@link #RECOVERY_MARGIN}, so a disk hovering around the limit does not open a new incident every few
 * minutes.
 */
final class StorageHealthPolicy {
    static final Duration STALE_AFTER = Duration.ofHours(26);
    static final Duration INTERRUPTED_AFTER = Duration.ofHours(3);
    static final double RECOVERY_MARGIN = 1.1;

    private StorageHealthPolicy() {
    }

    static BackupHealth backup(Optional<BackupState> recorded, Instant now) {
        if (recorded.isEmpty()) {
            return new BackupHealth(BackupHealth.NOT_CONFIGURED, null, null, null, null);
        }
        BackupState state = recorded.get();
        String verdict = verdict(state, now);
        return new BackupHealth(verdict, BackupHealth.FAILED.equals(verdict) ? state.failureCode() : null,
                state.lastSuccessAt(), state.lastAttemptAt(), state.runningSince());
    }

    static BackupHealth unreadable() {
        return new BackupHealth(BackupHealth.UNKNOWN, BackupHealth.STATE_UNREADABLE, null, null, null);
    }

    private static String verdict(BackupState state, Instant now) {
        if (state.runningSince() != null) {
            return now.isAfter(state.runningSince().plus(INTERRUPTED_AFTER)) ? BackupHealth.INTERRUPTED
                    : BackupHealth.RUNNING;
        }
        if (state.failureCode() != null) {
            return BackupHealth.FAILED;
        }
        if (!state.destinationVerified() || state.lastSuccessAt() == null) {
            return BackupHealth.NOT_CONFIGURED;
        }
        return now.isAfter(state.lastSuccessAt().plus(STALE_AFTER)) ? BackupHealth.STALE : BackupHealth.OK;
    }

    /** Free bytes below which a disk of this size is low: the higher of the two configured limits. */
    static long lowBelow(long totalBytes, StorageLimits limits) {
        return Math.max((long) Math.ceil(totalBytes * limits.minFreeFraction()), limits.minFreeBytes());
    }

    static DiskSpace disk(String name, DiskUsage usage, StorageLimits limits) {
        long lowBelow = lowBelow(usage.totalBytes(), limits);
        String state = usage.freeBytes() < lowBelow ? DiskSpace.LOW : DiskSpace.OK;
        return new DiskSpace(name, state, usage.totalBytes(), usage.freeBytes(), lowBelow, usage.measuredAt());
    }

    /** Whether a disk that was low has clearly recovered; see the class comment for the margin. */
    static boolean recovered(DiskSpace disk) {
        return disk.freeBytes() != null && disk.freeBytes() >= Math.ceil(disk.lowBelowBytes() * RECOVERY_MARGIN);
    }
}
