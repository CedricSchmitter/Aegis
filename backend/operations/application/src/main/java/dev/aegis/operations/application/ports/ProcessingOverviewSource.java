package dev.aegis.operations.application.ports;

import dev.aegis.shared.Actor;
import java.util.Map;

/** Document processing counts by state, provided by the documents context. */
public interface ProcessingOverviewSource {
    Map<String, Long> processingCounts(Actor actor);
}
