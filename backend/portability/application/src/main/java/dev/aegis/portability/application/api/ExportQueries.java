package dev.aegis.portability.application.api;

import dev.aegis.shared.Actor;
import java.util.List;
import java.util.Optional;

/** Published export generations, newest first, and the state of the daily export. */
public interface ExportQueries {
    List<ExportSummary> list(Actor actor);

    Optional<ExportSummary> latest(Actor actor);

    ExportStatus status(Actor actor);
}
