package dev.aegis.planning.application.api;

import dev.aegis.shared.Actor;
import java.util.List;

/** Public contract for portability. */
public interface TaskExportQuery {
    List<TaskExportRecord> exportAll(Actor actor);
}
