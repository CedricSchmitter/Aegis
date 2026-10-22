package dev.aegis.documents.application;

import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.DocumentRepository;
import dev.aegis.documents.domain.analysis.DocumentAnalysis;
import dev.aegis.documents.domain.analysis.DocumentAnalysisRepository;
import dev.aegis.documents.domain.classification.ClassificationRepository;
import dev.aegis.documents.domain.classification.DocumentClassification;
import dev.aegis.documents.domain.history.DocumentAction;
import dev.aegis.documents.domain.history.DocumentChange;
import dev.aegis.documents.domain.history.DocumentHistoryRepository;
import dev.aegis.documents.domain.history.DocumentSnapshot;
import dev.aegis.shared.ChangeActor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.FieldChange;
import dev.aegis.shared.NotFoundException;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Records a saved document change in the history (ADR-0029). The caller takes a {@link #snapshot} before the change
 * and calls {@link #record} after saving, inside the same transaction, so the entry commits or rolls back with it.
 */
public final class DocumentHistoryRecorder {
    private final DocumentRepository documents;
    private final ClassificationRepository classifications;
    private final DocumentAnalysisRepository analyses;
    private final DocumentHistoryRepository history;
    private final Clock clock;

    public DocumentHistoryRecorder(DocumentRepository documents, ClassificationRepository classifications,
            DocumentAnalysisRepository analyses, DocumentHistoryRepository history, Clock clock) {
        this.documents = documents;
        this.classifications = classifications;
        this.analyses = analyses;
        this.history = history;
        this.clock = clock;
    }

    /** The recorded fields as stored now. */
    public DocumentSnapshot snapshot(DocumentId id) {
        Document document = documents.findById(id).orElseThrow(() -> new NotFoundException("document",
                id.toString()));
        DocumentClassification classification = classifications.findById(id).orElse(null);
        String filename = analyses.findById(id).flatMap(DocumentAnalysis::filenameOverride).orElse(null);
        String folder = classification == null ? null
                : classification.categoryId().map(category -> category.value().toString()).orElse(null);
        Set<String> labels = classification == null ? Set.of()
                : classification.effectiveLabelIds().stream().map(label -> label.value().toString())
                        .collect(Collectors.toSet());
        return DocumentSnapshot.of(document.title(), filename, folder, labels);
    }

    /**
     * Records what changed since {@code before}. A member edit or analysis that changed none of the recorded fields
     * leaves no entry; the trash is recorded even without a field change.
     */
    public void record(DocumentSnapshot before, DocumentId id, ChangeActor actor, DocumentAction action) {
        List<FieldChange> changes = snapshot(id).changesSince(before);
        boolean alwaysRecorded = action == DocumentAction.TRASHED || action == DocumentAction.RESTORED;
        if (!changes.isEmpty() || alwaysRecorded) {
            history.append(DocumentChange.record(id, clock.instant(), actor, action, changes));
        }
    }

    void recordUndo(DocumentChange undone, DocumentSnapshot before, ChangeActor actor) {
        history.append(DocumentChange.undo(undone, clock.instant(), actor,
                snapshot(undone.documentId()).changesSince(before)));
    }
}
