package dev.aegis.documents.application.api;

import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import java.util.List;

/**
 * Public contract for portability: complete document records, their originals and the shared catalog. Open to
 * household processes (ADR-0029), so the daily export can read it.
 */
public interface DocumentExportQuery {
    List<DocumentExportRecord> exportAll(Actor actor);

    OriginalContent originalForExport(Actor actor, DocumentId id);

    CatalogExport exportCatalog(Actor actor);
}
