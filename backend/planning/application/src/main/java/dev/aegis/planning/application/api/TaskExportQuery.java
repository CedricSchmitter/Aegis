package dev.aegis.planning.application.api;

import dev.aegis.shared.Actor;
import java.util.List;

public interface TaskExportQuery {
    List<TaskExportRecord> exportAll(Actor actor);
}
