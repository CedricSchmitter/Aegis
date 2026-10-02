package dev.aegis.documents.application.api;

import dev.aegis.shared.Actor;
import java.util.UUID;

/**
 * Explicitly started classification of existing documents that were never classified. Only a member who
 * may use AI starts a run; it is never started automatically. Any member may cancel it.
 */
public interface BulkAnalysisCommands {
    /** {@code kind}: {@code ALL}, {@code UNSORTED} or {@code FOLDER} with {@code folderId}. */
    record ScopeRequest(String kind, UUID folderId, boolean includeSubfolders) {
    }

    /** Which documents a run would include and what it would cost; changes nothing. */
    BulkAnalysisPreview preview(Actor actor, ScopeRequest scope);

    /**
     * Starts a run over the documents the preview showed. {@code expectedDocuments} is the preview's count; when the
     * scope changed meanwhile, the start is refused with {@code analysis.bulk.changed} so the member sees the new
     * preview first.
     */
    BulkAnalysisView start(Actor actor, ScopeRequest scope, int expectedDocuments);

    /**
     * Cancels a running run: nothing more is requested, the runs in flight are cancelled and their documents return
     * to their state before the run. Results already applied stay.
     */
    BulkAnalysisView cancel(Actor actor, UUID id);
}
