package dev.aegis.documents.application.api;

import dev.aegis.shared.Actor;
import java.util.Map;

/** Operational visibility for the status page: archive health and processing counts. */
public interface DocumentStatusQuery {
    ArchiveHealth archiveHealth(Actor actor);

    /** Number of documents per processing state (RECEIVED, SUBMITTED, ARCHIVED, FAILED). */
    Map<String, Long> processingCounts(Actor actor);
}
