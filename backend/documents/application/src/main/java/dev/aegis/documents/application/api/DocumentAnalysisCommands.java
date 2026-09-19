package dev.aegis.documents.application.api;

import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import java.util.List;

/**
 * Member commands around the automatic analysis of documents. {@code expectedVersion} is
 * {@link AnalysisView#version()}. Requests are explicit and bounded: existing documents are only analyzed when a
 * member selects them.
 */
public interface DocumentAnalysisCommands {
    /** Documents one bulk request may select. */
    int MAX_BULK = 10;

    /** Outcome of a bulk request: which documents got a run and why others were skipped. */
    record BulkResult(List<DocumentId> requested, List<Skipped> skipped) {
    }

    /** {@code reason}: {@code document.notArchived}, {@code analysis.pending} or an AI availability code. */
    record Skipped(DocumentId documentId, String reason) {
    }

    /** Analyzes an archived document (first time, again, or after a failure). */
    DocumentSummary requestAnalysis(Actor actor, DocumentId id, long expectedVersion);

    /** Analyzes up to {@link #MAX_BULK} selected documents; each gets its own run and budget check. */
    BulkResult requestAnalyses(Actor actor, List<DocumentId> ids);

    /** Accepts the generated title (the member confirms it) or reverts/rejects it. */
    DocumentSummary decideTitle(Actor actor, DocumentId id, boolean accept, long expectedVersion);

    /** Accepts a new organization or topic name (creating or reusing the label and assigning it) or rejects it. */
    DocumentSummary decideCandidate(Actor actor, DocumentId id, String key, boolean accept, long expectedVersion);

    /** Accepts (adds to the document) or rejects a subject suggestion. */
    DocumentSummary decideSubject(Actor actor, DocumentId id, SubjectView subject, boolean accept,
            long expectedVersion);

    /** Sets the friendly filename stem chosen by a member; {@code null} returns to the generated name. */
    DocumentSummary setFilename(Actor actor, DocumentId id, String stem, long expectedVersion);
}
