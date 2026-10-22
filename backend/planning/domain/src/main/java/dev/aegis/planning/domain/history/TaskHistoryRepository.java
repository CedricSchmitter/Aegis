package dev.aegis.planning.domain.history;

import dev.aegis.shared.TaskId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Append-only task history (ADR-0030). Entries are written in the transaction of the change they describe and never
 * updated; they leave only together with their task, when it is deleted permanently.
 */
public interface TaskHistoryRepository {

    /** Appends the change unless one is already recorded for the same task version. */
    void append(TaskChange change);

    /** Newest first; {@code beforeVersion} continues a page, null starts at the newest. */
    List<TaskChange> findByTask(TaskId taskId, Long beforeVersion, int limit);

    Optional<TaskChange> find(UUID changeId);

    /** The undo that reversed {@code changeId}, if any. */
    Optional<UUID> undoneBy(UUID changeId);

    /** When the household began recording task history; earlier changes were never recorded. */
    Instant recordingSince();
}
