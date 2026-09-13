package dev.aegis.operations.adapters.sources;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class BackupStatusSourceTest {
    @TempDir
    Path dir;

    @Test
    void failureAndStaleSuccessCannotAppearConnected() throws Exception {
        Path file = dir.resolve("state.json");
        var source = new BackupStatusSource(file.toString(), JsonMapper.builder().build(),
                Clock.fixed(Instant.parse("2026-09-13T12:00:00Z"), ZoneOffset.UTC));
        assertThat(source.check(null).state()).isEqualTo("NOT_CONFIGURED");
        Files.writeString(file, """
                {"destinationVerified":true,"lastSuccessAt":"2026-09-10T12:00:00Z"}
                """);
        assertThat(source.check(null).state()).isEqualTo("DEGRADED");
        Files.writeString(file, """
                {"destinationVerified":true,"lastSuccessAt":"2026-09-13T11:00:00Z","error":"Backup failed"}
                """);
        assertThat(source.check(null).state()).isEqualTo("DEGRADED");
        assertThat(source.check(null).lastSuccessAt()).isEqualTo(Instant.parse("2026-09-13T11:00:00Z"));
        Files.writeString(file, """
                {"destinationVerified":true,"lastSuccessAt":"2026-09-13T11:00:00Z","error":null}
                """);
        assertThat(source.check(null).state()).isEqualTo("CONNECTED");
    }
}
