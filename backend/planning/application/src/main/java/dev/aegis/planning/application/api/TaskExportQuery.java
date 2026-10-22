package dev.aegis.planning.application.api;

import dev.aegis.shared.Actor;
import java.util.List;

/** Public contract for portability: every task. Open to household processes (ADR-0029) for the daily export. */
public interface TaskExportQuery {
    List<TaskExportRecord> exportAll(Actor actor);
}
