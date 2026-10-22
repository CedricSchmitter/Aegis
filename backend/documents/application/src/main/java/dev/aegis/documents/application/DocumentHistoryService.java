package dev.aegis.documents.application;

import dev.aegis.documents.application.api.DocumentHistory;
import dev.aegis.documents.application.api.DocumentSummary;
import dev.aegis.documents.application.ports.CatalogLock;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.DocumentRepository;
import dev.aegis.documents.domain.analysis.DocumentAnalysis;
import dev.aegis.documents.domain.analysis.DocumentAnalysisRepository;
import dev.aegis.documents.domain.catalog.CategoryId;
import dev.aegis.documents.domain.catalog.CategoryRepository;
import dev.aegis.documents.domain.catalog.Label;
import dev.aegis.documents.domain.catalog.LabelId;
import dev.aegis.documents.domain.catalog.LabelRepository;
import dev.aegis.documents.domain.catalog.LabelVocabulary;
import dev.aegis.documents.domain.classification.ClassificationRepository;
import dev.aegis.documents.domain.classification.DocumentClassification;
import dev.aegis.documents.domain.classification.LabelRef;
import dev.aegis.documents.domain.history.DocumentChange;
import dev.aegis.documents.domain.history.DocumentField;
import dev.aegis.documents.domain.history.DocumentHistoryRepository;
import dev.aegis.documents.domain.history.DocumentSnapshot;
import dev.aegis.documents.domain.placement.PlacementSuggestionRepository;
import dev.aegis.shared.Actor;
import dev.aegis.shared.ChangeActor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.FieldChange;
import dev.aegis.shared.HouseholdAccess;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.NotFoundException;
import dev.aegis.shared.UndoConflictException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reads a document's recorded history and undoes a member's edit of its title, friendly filename, folder or labels
 * (ADR-0029). The undo uses the same domain operations as the original commands, so a restored automatic label is
 * remembered as rejected and a restored folder counts as the member's decision. Wrapped in a transaction.
 */
public class DocumentHistoryService implements DocumentHistory {
    public static final int MAX_PAGE = 100;

    private final DocumentRepository documents;
    private final ClassificationRepository classifications;
    private final DocumentAnalysisRepository analyses;
    private final CategoryRepository categories;
    private final LabelRepository labels;
    private final PlacementSuggestionRepository placements;
    private final DocumentHistoryRepository history;
    private final DocumentHistoryRecorder recorder;
    private final CatalogLock lock;
    private final DocumentPresenter presenter;
    private final HouseholdAccess access;
    private final Clock clock;

    public DocumentHistoryService(DocumentRepository documents, ClassificationRepository classifications,
            DocumentAnalysisRepository analyses, CategoryRepository categories, LabelRepository labels,
            PlacementSuggestionRepository placements, DocumentHistoryRepository history,
            DocumentHistoryRecorder recorder, CatalogLock lock, DocumentPresenter presenter, HouseholdAccess access,
            Clock clock) {
        this.documents = documents;
        this.classifications = classifications;
        this.analyses = analyses;
        this.categories = categories;
        this.labels = labels;
        this.placements = placements;
        this.history = history;
        this.recorder = recorder;
        this.lock = lock;
        this.presenter = presenter;
        this.access = access;
        this.clock = clock;
    }

    @Override
    public DocumentHistoryPage history(Actor actor, DocumentId id, Long beforeSequence, int limit) {
        access.requireMember(actor);
        if (limit < 1 || limit > MAX_PAGE) {
            throw new InvalidInputException("validation.failed", "limit must be between 1 and " + MAX_PAGE);
        }
        Document document = load(id);
        DocumentSnapshot current = recorder.snapshot(id);
        List<DocumentChange> page = history.findByDocument(id, beforeSequence, limit + 1);
        boolean more = page.size() > limit;
        List<DocumentChangeView> views = page.stream().limit(limit)
                .map(change -> view(change, document, current)).toList();
        Long next = more ? views.getLast().sequence() : null;
        return new DocumentHistoryPage(id, history.recordingSince(), views, next);
    }

    @Override
    public DocumentSummary undo(Actor actor, DocumentId id, UUID changeId) {
        access.requireMember(actor);
        lock.shared();
        DocumentChange change = history.find(changeId).filter(c -> c.documentId().equals(id))
                .orElseThrow(() -> new NotFoundException("documentChange", changeId.toString()));
        if (!change.undoable()) {
            throw UndoConflictException.notSupported(changeId.toString());
        }
        if (history.undoneBy(changeId).isPresent()) {
            throw UndoConflictException.alreadyUndone(changeId.toString());
        }
        Document document = load(id);
        document.requireNotTrashed();
        DocumentSnapshot before = recorder.snapshot(id);
        List<String> overwritten = change.overwrittenIn(before);
        if (!overwritten.isEmpty()) {
            throw UndoConflictException.changedSince(changeId.toString(), String.join(",", overwritten));
        }
        Instant now = clock.instant();
        for (FieldChange field : change.changes()) {
            switch (DocumentField.ofCode(field.field())) {
                case TITLE -> {
                    document.rename(field.before());
                    documents.save(document);
                }
                case FILENAME -> {
                    DocumentAnalysis analysis = analyses.findById(id).orElseThrow(() -> new NotFoundException(
                            "documentAnalysis", id.toString()));
                    analysis.overrideFilename(field.before(), actor.memberId(), now);
                    analyses.save(analysis);
                }
                case FOLDER -> {
                    DocumentClassification classification = classification(id);
                    classification.placeIn(folder(change, field.before()), actor.memberId(), now);
                    classifications.save(classification);
                    placements.delete(id);
                }
                case LABELS -> {
                    DocumentClassification classification = classification(id);
                    var target = DocumentChange.labelsUndone(field, before.labels());
                    classification.setLabels(labelRefs(change, target, before.labels()), actor.memberId(), now);
                    classifications.save(classification);
                }
            }
        }
        recorder.recordUndo(change, before, ChangeActor.of(actor));
        return presenter.summarize(load(id));
    }

    private DocumentChangeView view(DocumentChange change, Document document, DocumentSnapshot current) {
        UUID undoneBy = change.undoable() ? history.undoneBy(change.id()).orElse(null) : null;
        boolean undoable = change.undoable() && undoneBy == null && !document.isTrashed()
                && change.overwrittenIn(current).isEmpty();
        return new DocumentChangeView(change.id(), change.sequence(), change.occurredAt(),
                change.actor().kind().name(), change.actor().member(), change.actor().reference(),
                change.action().name(), change.changes(), change.undoOf(), undoneBy, undoable);
    }

    /** The earlier folder, which must still exist; {@code null} is Unsorted. */
    private CategoryId folder(DocumentChange change, String value) {
        if (value == null) {
            return null;
        }
        return categories.load().find(new CategoryId(UUID.fromString(value))).map(category -> category.id())
                .orElseThrow(() -> new UndoConflictException("folderGone", change.id().toString(),
                        "the earlier folder no longer exists", Map.of("folderId", value)));
    }

    /** Resolves the wanted labels; one that is gone or retired and not on the document now cannot come back. */
    private Set<LabelRef> labelRefs(DocumentChange change, Set<String> wanted, Set<String> current) {
        LabelVocabulary vocabulary = labels.load();
        Set<LabelRef> refs = new LinkedHashSet<>();
        for (String raw : wanted) {
            Label label;
            try {
                label = vocabulary.resolve(new LabelId(UUID.fromString(raw)));
            } catch (NotFoundException gone) {
                throw labelGone(change, raw);
            }
            if (!label.acceptsAssignments() && !current.contains(raw)) {
                throw labelGone(change, raw);
            }
            refs.add(new LabelRef(label.id(), label.facet()));
        }
        return refs;
    }

    private static UndoConflictException labelGone(DocumentChange change, String labelId) {
        return new UndoConflictException("labelGone", change.id().toString(),
                "the earlier label no longer exists or is retired", Map.of("labelId", labelId));
    }

    private DocumentClassification classification(DocumentId id) {
        return classifications.findById(id).orElseGet(() -> DocumentClassification.unsorted(id));
    }

    private Document load(DocumentId id) {
        return documents.findById(id).orElseThrow(() -> new NotFoundException("document", id.toString()));
    }
}
