package dev.aegis.planning.application.api;

import dev.aegis.shared.Actor;

/** Restores a task from an export with its stable id and source link; existing ids are left untouched. */
public interface ImportTask {
    enum ImportOutcome {
        IMPORTED,
        ALREADY_PRESENT
    }

    ImportOutcome importTask(Actor actor, TaskExportRecord record);
}
