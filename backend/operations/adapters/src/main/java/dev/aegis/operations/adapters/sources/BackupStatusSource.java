package dev.aegis.operations.adapters.sources;

import dev.aegis.operations.application.api.IntegrationStatus;
import dev.aegis.operations.application.ports.IntegrationStatusSource;
import dev.aegis.shared.Actor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * External backup state written by the backup script (see ops/backup). Without a verified destination and a
 * successful run, the status is reported as not configured; a local restore drill never counts as a backup.
 */
@Component
public class BackupStatusSource implements IntegrationStatusSource {
    private final Path stateFile;
    private final ObjectMapper mapper;
    private final Clock clock;

    public BackupStatusSource(@Value("${aegis.backup.state-file:}") String stateFile, ObjectMapper mapper,
            Clock clock) {
        this.stateFile = stateFile == null || stateFile.isBlank() ? null : Path.of(stateFile);
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public IntegrationStatus check(Actor actor) {
        if (stateFile == null || !Files.isRegularFile(stateFile)) {
            return IntegrationStatus.notConfigured("externalBackup",
                    "no external backup destination verified; local export/restore drill only");
        }
        try {
            JsonNode state = mapper.readTree(Files.readAllBytes(stateFile));
            boolean verified = state.path("destinationVerified").asBoolean(false);
            String lastSuccess = state.path("lastSuccessAt").asText(null);
            String repository = state.path("repository").asText(null);
            if (!verified || lastSuccess == null) {
                return IntegrationStatus.notConfigured("externalBackup",
                        "backup destination not verified or no successful run recorded");
            }
            return new IntegrationStatus("externalBackup", IntegrationStatus.CONNECTED,
                    repository == null ? null : "repository " + repository, Instant.parse(lastSuccess),
                    clock.instant());
        } catch (IOException | RuntimeException e) {
            return new IntegrationStatus("externalBackup", IntegrationStatus.UNAVAILABLE,
                    "backup state unreadable: " + e.getMessage(), null, clock.instant());
        }
    }
}
