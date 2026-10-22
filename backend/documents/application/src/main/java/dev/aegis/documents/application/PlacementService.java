package dev.aegis.documents.application;

import dev.aegis.documents.application.api.DocumentSummary;
import dev.aegis.documents.application.api.PlacementCommands;
import dev.aegis.documents.application.ports.CatalogLock;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.DocumentRepository;
import dev.aegis.documents.domain.catalog.Category;
import dev.aegis.documents.domain.catalog.CategoryId;
import dev.aegis.documents.domain.catalog.CategoryRepository;
import dev.aegis.documents.domain.catalog.CategoryTree;
import dev.aegis.documents.domain.catalog.LabelRepository;
import dev.aegis.documents.domain.catalog.LabelVocabulary;
import dev.aegis.documents.domain.classification.ClassificationRepository;
import dev.aegis.documents.domain.classification.DocumentClassification;
import dev.aegis.documents.domain.history.DocumentAction;
import dev.aegis.documents.domain.history.DocumentSnapshot;
import dev.aegis.documents.domain.placement.PlacementSuggestion;
import dev.aegis.documents.domain.placement.PlacementSuggestionRepository;
import dev.aegis.documents.domain.placement.PlacementTarget;
import dev.aegis.shared.Actor;
import dev.aegis.shared.ChangeActor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.HouseholdAccess;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.NotFoundException;
import dev.aegis.shared.StaleVersionException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Member decisions about proposed folder placements (ADR-0005). Accepting a proposed path creates the
 * missing folders and moves the document in one transaction under the exclusive catalog lock, with optimistic
 * concurrency on the anchor folder and on the document's classification. Aegis never creates folders on its own;
 * every folder here is the result of a member accepting a proposal they could edit first.
 */
public class PlacementService implements PlacementCommands {
    /** How many documents one acceptance may move when the same path was proposed for several of them. */
    public static final int MAX_GROUP = 200;

    private final ClassificationRepository classifications;
    private final PlacementSuggestionRepository placements;
    private final CategoryRepository categories;
    private final LabelRepository labels;
    private final DocumentRepository documents;
    private final CatalogLock lock;
    private final DocumentPresenter presenter;
    private final HouseholdAccess access;
    private final Clock clock;
    private final DocumentHistoryRecorder history;

    public PlacementService(ClassificationRepository classifications, PlacementSuggestionRepository placements,
            CategoryRepository categories, LabelRepository labels, DocumentRepository documents, CatalogLock lock,
            DocumentPresenter presenter, HouseholdAccess access, Clock clock, DocumentHistoryRecorder history) {
        this.classifications = classifications;
        this.placements = placements;
        this.categories = categories;
        this.labels = labels;
        this.documents = documents;
        this.lock = lock;
        this.presenter = presenter;
        this.access = access;
        this.clock = clock;
        this.history = history;
    }

    @Override
    public AcceptedPlacement acceptPlacement(Actor actor, DocumentId id, AcceptPlacement command) {
        access.requireMember(actor);
        lock.exclusive();
        Document document = requireDocument(id);
        document.requireNotTrashed();
        PlacementSuggestion suggestion = placements.findById(id).orElseThrow(() -> new InvalidInputException(
                "placement.suggestion.missing", "There is no pending placement suggestion"));
        DocumentClassification classification = loadVersion(id, command.expectedVersion());
        CategoryTree tree = categories.load();
        Instant now = clock.instant();

        CategoryId anchor = requireAnchor(tree, command);
        // Validates the member's edits - one target, usable names, at most three new folders - before any write.
        PlacementTarget wanted = new PlacementTarget(
                command.folderId() == null ? null : new CategoryId(command.folderId()), anchor, command.newPath());
        CategoryId target;
        int createdFolders = 0;
        if (wanted.isExisting()) {
            target = tree.require(wanted.folder()).id();
        } else {
            CategoryTree.CreatedPath created = tree.createPath(anchor, wanted.newPath(), actor.memberId(), now);
            target = created.target();
            createdFolders = created.created().size();
            if (createdFolders > 0) {
                LabelVocabulary vocabulary = labels.load();
                categories.save(tree, tree.effects(vocabulary::facetOf));
            }
        }
        int moved = move(classification, target, actor, now);
        if (command.alsoForOtherDocuments()) {
            moved += moveGroup(id, suggestion.key(), target, actor, now);
        }
        return new AcceptedPlacement(presenter.summarize(requireDocument(document.id())), target.value(),
                categories.load().pathNames(target), createdFolders, moved);
    }

    @Override
    public DocumentSummary rejectPlacement(Actor actor, DocumentId id, long expectedVersion) {
        access.requireMember(actor);
        lock.shared();
        Document document = requireDocument(id);
        document.requireNotTrashed();
        PlacementSuggestion suggestion = placements.findById(id).orElseThrow(() -> new InvalidInputException(
                "placement.suggestion.missing", "There is no pending placement suggestion"));
        loadVersion(id, expectedVersion);
        placements.reject(id, suggestion.key(), actor.memberId(), clock.instant());
        placements.delete(id);
        return presenter.summarize(document);
    }

    @Override
    public DocumentSummary revertPlacement(Actor actor, DocumentId id, long expectedVersion) {
        access.requireMember(actor);
        lock.shared();
        Document document = requireDocument(id);
        document.requireNotTrashed();
        DocumentClassification classification = loadVersion(id, expectedVersion);
        CategoryId reverted = classification.categoryId().orElse(null);
        Instant now = clock.instant();
        DocumentSnapshot before = history.snapshot(id);
        classification.revertPlacement(actor.memberId(), now);
        classifications.save(classification);
        history.record(before, id, ChangeActor.of(actor), DocumentAction.MOVED);
        if (reverted != null) {
            // The member does not want this folder for this document; later runs must not propose it again.
            placements.reject(id, PlacementTarget.existing(reverted).key(), actor.memberId(), now);
        }
        placements.delete(id);
        return presenter.summarize(document);
    }

    /** Moves the other documents whose pending suggestion names the same target. */
    private int moveGroup(DocumentId accepted, String key, CategoryId target, Actor actor, Instant now) {
        int moved = 0;
        List<PlacementSuggestion> group = placements.findByKey(key);
        for (PlacementSuggestion other : group) {
            if (other.documentId().equals(accepted) || moved >= MAX_GROUP) {
                continue;
            }
            DocumentClassification classification = classifications.findById(other.documentId()).orElse(null);
            // A document in the trash keeps its suggestion but is not moved along with the visible ones.
            boolean active = documents.findById(other.documentId()).filter(d -> !d.isTrashed()).isPresent();
            if (classification == null || !active) {
                continue;
            }
            moved += move(classification, target, actor, now);
        }
        return moved;
    }

    private int move(DocumentClassification classification, CategoryId target, Actor actor, Instant now) {
        DocumentSnapshot before = history.snapshot(classification.documentId());
        boolean changed = classification.placeIn(target, actor.memberId(), now);
        classifications.save(classification);
        history.record(before, classification.documentId(), ChangeActor.of(actor), DocumentAction.MOVED);
        placements.delete(classification.documentId());
        return changed ? 1 : 0;
    }

    /** The anchor must still be the folder the member saw, so a concurrent tree edit is reported, not ignored. */
    private CategoryId requireAnchor(CategoryTree tree, AcceptPlacement command) {
        if (command.anchorFolderId() == null) {
            return null;
        }
        Category anchor = tree.require(new CategoryId(command.anchorFolderId()));
        if (command.expectedAnchorVersion() != null && command.expectedAnchorVersion() != anchor.version()) {
            throw new StaleVersionException("category", anchor.id().toString(), command.expectedAnchorVersion(),
                    anchor.version());
        }
        return anchor.id();
    }

    private Document requireDocument(DocumentId id) {
        return documents.findById(id).orElseThrow(() -> new NotFoundException("document", id.toString()));
    }

    private DocumentClassification loadVersion(DocumentId id, long expectedVersion) {
        DocumentClassification classification = classifications.findById(id)
                .orElseGet(() -> DocumentClassification.unsorted(id));
        if (classification.version() != expectedVersion) {
            throw new StaleVersionException("documentClassification", id.toString(), expectedVersion,
                    classification.version());
        }
        return classification;
    }
}
