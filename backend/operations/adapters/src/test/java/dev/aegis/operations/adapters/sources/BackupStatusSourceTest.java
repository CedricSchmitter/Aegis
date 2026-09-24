package dev.aegis.operations.adapters.sources;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.operations.application.StorageHealthService;
import dev.aegis.operations.application.StorageLimits;
import dev.aegis.operations.application.api.IntegrationStatus;
import dev.aegis.operations.application.ports.BackupStateSource.BackupState;
import dev.aegis.operations.application.ports.DiskUsage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BackupStatusSourceTest {
    private static final Instant NOW = Instant.parse("2026-09-13T12:00:00Z");

    @Test
    void failureStaleSuccessAndInterruptionCannotAppearConnected() {
        assertThat(check(null).state()).isEqualTo(IntegrationStatus.NOT_CONFIGURED);

        IntegrationStatus stale = check(new BackupState(true, NOW.minus(Duration.ofDays(3)), NOW, null, null, null));
        assertThat(stale.state()).isEqualTo(IntegrationStatus.DEGRADED);
        assertThat(stale.detail()).isEqualTo("backup stale");

        IntegrationStatus failed = check(new BackupState(true, NOW.minus(Duration.ofHours(1)), NOW, null,
                "destination.notMounted", null));
        assertThat(failed.state()).isEqualTo(IntegrationStatus.DEGRADED);
        assertThat(failed.detail()).isEqualTo("backup failed: destination.notMounted");
        assertThat(failed.lastSuccessAt()).isEqualTo(NOW.minus(Duration.ofHours(1)));

        assertThat(check(new BackupState(true, NOW.minus(Duration.ofDays(1)), NOW, NOW.minus(Duration.ofHours(4)),
                null, null)).state()).isEqualTo(IntegrationStatus.DEGRADED);
    }

    @Test
    void aSuccessfulOrRunningBackupIsConnected() {
        IntegrationStatus ok = check(new BackupState(true, NOW.minus(Duration.ofHours(1)), NOW, null, null, null));
        assertThat(ok.state()).isEqualTo(IntegrationStatus.CONNECTED);
        assertThat(ok.lastSuccessAt()).isEqualTo(NOW.minus(Duration.ofHours(1)));

        IntegrationStatus running = check(new BackupState(true, NOW.minus(Duration.ofHours(24)), NOW,
                NOW.minus(Duration.ofMinutes(1)), null, null));
        assertThat(running.state()).isEqualTo(IntegrationStatus.CONNECTED);
        assertThat(running.detail()).isEqualTo("backup running");
    }

    private static IntegrationStatus check(BackupState state) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var storage = new StorageHealthService(() -> Optional.ofNullable(state),
                () -> new DiskUsage(100, 50, NOW), new StorageLimits(0.1, 0), clock);
        return new BackupStatusSource(storage, clock).check(null);
    }
}
