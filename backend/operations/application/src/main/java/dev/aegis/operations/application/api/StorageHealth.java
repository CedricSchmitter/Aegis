package dev.aegis.operations.application.api;

import java.util.List;

/**
 * The external backup and the free space of the disks Aegis depends on (issue #100). The backup disk is listed once
 * the backup script has measured it.
 */
public record StorageHealth(BackupHealth backup, List<DiskSpace> disks) {
    public StorageHealth {
        disks = List.copyOf(disks);
    }
}
