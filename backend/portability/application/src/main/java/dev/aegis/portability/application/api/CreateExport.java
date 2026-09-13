package dev.aegis.portability.application.api;

import dev.aegis.shared.Actor;

/** Produces a complete, atomically published export generation. */
public interface CreateExport {
    ExportSummary export(Actor actor);
}
