package dev.aegis.operations.adapters.storage;

import dev.aegis.operations.application.ports.BackupStateSource;
import dev.aegis.operations.application.ports.DiskUsage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the state file of the host's backup script (ops/backup/backup.py), mounted read-only into the container.
 *
 * <p>Files written before schema 2 have no {@code failureCode} and no {@code runningSince}; their free-text
 * {@code error} is mapped: "Backup in progress" becomes a run that started at the last attempt, any other text the
 * failure code {@link #LEGACY_FAILURE}. The first run of the new script clears {@code error}.
 */
@Component
public class FileBackupStateSource implements BackupStateSource {
    static final String LEGACY_FAILURE = "unknown";
    private static final String LEGACY_RUNNING = "Backup in progress";

    private final Path stateFile;
    private final ObjectMapper mapper;

    public FileBackupStateSource(@Value("${aegis.backup.state-file:}") String stateFile, ObjectMapper mapper) {
        this.stateFile = stateFile == null || stateFile.isBlank() ? null : Path.of(stateFile);
        this.mapper = mapper;
    }

    @Override
    public Optional<BackupState> read() {
        if (stateFile == null || !Files.isRegularFile(stateFile)) {
            return Optional.empty();
        }
        JsonNode state;
        try {
            state = mapper.readTree(Files.readAllBytes(stateFile));
        } catch (IOException e) {
            throw new UncheckedIOException("backup state unreadable", e);
        }
        if (!state.isObject()) {
            throw new IllegalStateException("backup state is not a JSON object");
        }
        Instant lastAttempt = instant(state, "lastAttemptAt");
        Instant runningSince = instant(state, "runningSince");
        String failureCode = text(state, "failureCode");
        String legacyError = text(state, "error");
        if (legacyError != null && failureCode == null && runningSince == null) {
            if (LEGACY_RUNNING.equals(legacyError)) {
                runningSince = lastAttempt;
            } else {
                failureCode = LEGACY_FAILURE;
            }
        }
        return Optional.of(new BackupState(state.path("destinationVerified").asBoolean(false),
                instant(state, "lastSuccessAt"), lastAttempt, runningSince, failureCode, disk(state.path("backupDisk"))));
    }

    private static DiskUsage disk(JsonNode disk) {
        if (!disk.isObject() || !disk.path("totalBytes").isIntegralNumber() || !disk.path("freeBytes").isIntegralNumber()
                || instant(disk, "measuredAt") == null) {
            return null;
        }
        return new DiskUsage(disk.path("totalBytes").asLong(), disk.path("freeBytes").asLong(),
                instant(disk, "measuredAt"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() && !value.asString().isBlank() ? value.asString() : null;
    }

    private static Instant instant(JsonNode node, String field) {
        String value = text(node, field);
        return value == null ? null : Instant.parse(value);
    }
}
