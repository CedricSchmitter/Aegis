package dev.aegis.documents.application.api;

import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;

/**
 * Restores an exported analysis. It is applied only while the local document has no analysis activity of its own,
 * so a restore never overwrites local results or decisions, and it never starts a model run.
 */
public interface ImportDocumentAnalysis {
    enum Outcome {
        APPLIED,
        KEPT_EXISTING,
        NOTHING_TO_APPLY
    }

    Outcome importAnalysis(Actor actor, DocumentId id, AnalysisRecord record);
}
