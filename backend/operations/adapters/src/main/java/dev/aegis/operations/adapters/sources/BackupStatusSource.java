package dev.aegis.operations.adapters.sources;

import dev.aegis.operations.application.StorageHealthService;
import dev.aegis.operations.application.api.BackupHealth;
import dev.aegis.operations.application.api.IntegrationStatus;
import dev.aegis.operations.application.ports.IntegrationStatusSource;
import dev.aegis.shared.Actor;
import java.time.Clock;
import org.springframework.stereotype.Component;

/**
 * The external backup in the integration list, derived from the storage health (ADR-0015); the status page shows the
 * details and a localized reason in its backup section. Without a verified destination and a successful run it is
 * reported as not configured; a local restore drill never counts as a backup.
 */
@Component
public class BackupStatusSource implements IntegrationStatusSource {
    private final StorageHealthService storage;
    private final Clock clock;

    public BackupStatusSource(StorageHealthService storage, Clock clock) {
        this.storage = storage;
        this.clock = clock;
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public IntegrationStatus check(Actor actor) {
        BackupHealth backup = storage.current().backup();
        return switch (backup.state()) {
            case BackupHealth.NOT_CONFIGURED -> IntegrationStatus.notConfigured("externalBackup",
                    "backup destination not verified or no successful run recorded");
            case BackupHealth.OK, BackupHealth.RUNNING -> new IntegrationStatus("externalBackup",
                    IntegrationStatus.CONNECTED, BackupHealth.RUNNING.equals(backup.state()) ? "backup running" : null,
                    backup.lastSuccessAt(), clock.instant());
            case BackupHealth.UNKNOWN -> new IntegrationStatus("externalBackup", IntegrationStatus.UNAVAILABLE,
                    "backup state unreadable", null, clock.instant());
            default -> new IntegrationStatus("externalBackup", IntegrationStatus.DEGRADED,
                    backup.failureCode() == null ? "backup " + backup.state().toLowerCase()
                            : "backup failed: " + backup.failureCode(),
                    backup.lastSuccessAt(), clock.instant());
        };
    }
}
