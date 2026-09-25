package dev.aegis.operations.application.ports;

import java.time.Instant;
import java.util.Optional;

/** The state the host's backup script records after each attempt (ops/backup, ADR-0015). */
public interface BackupStateSource {
    /**
     * @return empty when no backup has been set up (no state file)
     * @throws RuntimeException when a state file exists but cannot be read or parsed
     */
    Optional<BackupState> read();

    /**
     * One recorded state. {@code runningSince} is set while a run is active, {@code failureCode} after a failed
     * attempt, {@code backupDisk} after the last attempt that found the backup disk mounted.
     */
    record BackupState(boolean destinationVerified, Instant lastSuccessAt, Instant lastAttemptAt,
            Instant runningSince, String failureCode, DiskUsage backupDisk) {
    }
}
