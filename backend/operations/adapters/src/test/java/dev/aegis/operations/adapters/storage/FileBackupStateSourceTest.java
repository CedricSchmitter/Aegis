package dev.aegis.operations.adapters.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.operations.application.ports.BackupStateSource.BackupState;
import dev.aegis.operations.application.ports.DiskUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class FileBackupStateSourceTest {
    @TempDir
    Path dir;

    @Test
    void withoutAStateFileNoBackupIsSetUp() {
        assertThat(source("").read()).isEmpty();
        assertThat(source(dir.resolve("missing.json").toString()).read()).isEmpty();
    }

    @Test
    void readsTheSchemaTwoStateOfTheBackupScript() throws Exception {
        write("""
                {"schemaVersion":2,"destinationVerified":true,"lastSuccessAt":"2026-09-23T01:30:03+00:00",
                 "lastAttemptAt":"2026-09-24T01:30:02+00:00","runningSince":null,"error":null,
                 "failureCode":"check.failed","repository":"/mnt/aegis-backup/aegis-backup/restic",
                 "backupDisk":{"totalBytes":1000204886016,"freeBytes":999900000000,
                               "measuredAt":"2026-09-24T01:31:40+00:00"}}
                """);

        assertThat(source().read()).contains(new BackupState(true, Instant.parse("2026-09-23T01:30:03Z"),
                Instant.parse("2026-09-24T01:30:02Z"), null, "check.failed",
                new DiskUsage(1000204886016L, 999900000000L, Instant.parse("2026-09-24T01:31:40Z"))));
    }

    @Test
    void mapsTheFreeTextErrorOfTheEarlierScript() throws Exception {
        write("""
                {"destinationVerified":true,"lastSuccessAt":"2026-09-22T01:30:03Z",
                 "lastAttemptAt":"2026-09-23T01:30:00Z","error":"Backup in progress"}
                """);
        BackupState running = source().read().orElseThrow();
        assertThat(running.runningSince()).isEqualTo(Instant.parse("2026-09-23T01:30:00Z"));
        assertThat(running.failureCode()).isNull();

        write("""
                {"destinationVerified":false,"lastSuccessAt":"2026-09-22T01:30:03Z",
                 "lastAttemptAt":"2026-09-23T01:30:00Z","error":"Backup failed: ValueError"}
                """);
        BackupState failed = source().read().orElseThrow();
        assertThat(failed.failureCode()).isEqualTo("unknown");
        assertThat(failed.runningSince()).isNull();
        assertThat(failed.backupDisk()).isNull();

        // The state the VM had before this change: a success and no error.
        write("""
                {"destinationVerified":true,"lastSuccessAt":"2026-09-24T01:30:03Z",
                 "lastAttemptAt":"2026-09-24T01:30:03Z","repository":"/r","error":null}
                """);
        assertThat(source().read()).contains(new BackupState(true, Instant.parse("2026-09-24T01:30:03Z"),
                Instant.parse("2026-09-24T01:30:03Z"), null, null, null));
    }

    @Test
    void anIncompleteDiskMeasurementIsIgnoredAndABrokenFileIsAnError() throws Exception {
        write("""
                {"destinationVerified":true,"lastSuccessAt":"2026-09-24T01:30:03Z",
                 "backupDisk":{"totalBytes":"many","freeBytes":1}}
                """);
        assertThat(source().read().orElseThrow().backupDisk()).isNull();

        write("{\"destinationVerified\":true,");
        assertThatThrownBy(() -> source().read()).isInstanceOf(RuntimeException.class);
        write("[]");
        assertThatThrownBy(() -> source().read()).isInstanceOf(IllegalStateException.class);
        write("{\"lastSuccessAt\":\"yesterday\"}");
        assertThatThrownBy(() -> source().read()).isInstanceOf(RuntimeException.class);
    }

    private void write(String json) throws Exception {
        Files.writeString(dir.resolve("state.json"), json);
    }

    private FileBackupStateSource source() {
        return source(dir.resolve("state.json").toString());
    }

    private static FileBackupStateSource source(String file) {
        return new FileBackupStateSource(file, JsonMapper.builder().build());
    }
}
