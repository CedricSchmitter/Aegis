package dev.aegis.operations.application;

import dev.aegis.operations.application.api.BackupHealth;
import dev.aegis.operations.application.api.DiskSpace;
import dev.aegis.operations.application.api.StorageHealth;
import dev.aegis.operations.application.ports.BackupStateSource;
import dev.aegis.operations.application.ports.BackupStateSource.BackupState;
import dev.aegis.operations.application.ports.DataStorageSource;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads the backup state and measures the disks for the status page and the storage watch (ADR-0015). Callers
 * authorize: the status page requires a member, the watch runs as a household process. An unreadable state file or
 * an unmeasurable disk is reported as UNKNOWN instead of failing the whole view.
 */
public class StorageHealthService {
    private final BackupStateSource backupStates;
    private final DataStorageSource dataStorage;
    private final StorageLimits limits;
    private final Clock clock;

    public StorageHealthService(BackupStateSource backupStates, DataStorageSource dataStorage, StorageLimits limits,
            Clock clock) {
        this.backupStates = backupStates;
        this.dataStorage = dataStorage;
        this.limits = limits;
        this.clock = clock;
    }

    public StorageHealth current() {
        Instant now = clock.instant();
        Optional<BackupState> recorded;
        BackupHealth backup;
        try {
            recorded = backupStates.read();
            backup = StorageHealthPolicy.backup(recorded, now);
        } catch (RuntimeException unreadable) {
            recorded = Optional.empty();
            backup = StorageHealthPolicy.unreadable();
        }
        List<DiskSpace> disks = new ArrayList<>();
        try {
            disks.add(StorageHealthPolicy.disk(DiskSpace.DATA, dataStorage.measure(), limits));
        } catch (RuntimeException unmeasurable) {
            disks.add(DiskSpace.unknown(DiskSpace.DATA, now));
        }
        recorded.map(BackupState::backupDisk)
                .ifPresent(usage -> disks.add(StorageHealthPolicy.disk(DiskSpace.BACKUP, usage, limits)));
        return new StorageHealth(backup, disks);
    }
}
