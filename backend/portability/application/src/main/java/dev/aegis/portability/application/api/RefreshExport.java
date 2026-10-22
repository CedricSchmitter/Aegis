package dev.aegis.portability.application.api;

import dev.aegis.shared.Actor;
import java.util.List;

/**
 * The daily export (ADR-0029). It runs as the {@link #EXPORTER} household process, which may read every record the
 * export contains but change none. A new generation is written only when the household data differs from the latest
 * published generation; otherwise the run only records that the latest generation is still current.
 */
public interface RefreshExport {
    Actor EXPORTER = Actor.householdProcess("exporter");

    enum Outcome {
        /** A new generation was published. */
        CREATED,
        /** The data equals the latest generation's data; nothing was written. */
        UNCHANGED
    }

    /**
     * @param current the generation that is current after this run
     * @param pruned older scheduled generations removed by the retention rule; manual ones are never removed
     */
    record Result(Outcome outcome, ExportSummary current, List<String> pruned) {
        public Result {
            pruned = List.copyOf(pruned);
        }
    }

    /**
     * @throws dev.aegis.shared.NotAuthorizedException unless the actor is {@link #EXPORTER}
     */
    Result refresh(Actor actor);
}
