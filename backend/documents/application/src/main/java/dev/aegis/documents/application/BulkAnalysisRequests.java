package dev.aegis.documents.application;

import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;

/**
 * The per-document side of a bulk analysis (issue #115). A bulk run requests each document the same way a member's
 * selection does, so the same checks, generations, keys and policies apply.
 */
public interface BulkAnalysisRequests {
    sealed interface Result permits Requested, Skipped, Refused {
    }

    /** The document's run was requested with {@code generation}, in the caller's transaction. */
    record Requested(int generation) implements Result {
    }

    /** The document no longer qualifies ({@code document.trashed}, {@code analysis.classified}, ...). */
    record Skipped(String reason) implements Result {
    }

    /** The AI boundary refuses requests of this member now ({@code ai.notConfigured}, ...); nothing changed. */
    record Refused(String code) implements Result {
    }

    /** Requests the document's classification on behalf of {@code requester} with the trigger {@code BULK}. */
    Result requestForBulk(Actor requester, DocumentId id);

    /** Takes back the request of {@code generation} after its run was cancelled; false when it already ended. */
    boolean withdraw(DocumentId id, int generation);
}
