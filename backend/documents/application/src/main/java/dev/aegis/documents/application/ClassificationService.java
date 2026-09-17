package dev.aegis.documents.application;

import dev.aegis.documents.application.api.ClassificationCommands;
import dev.aegis.documents.application.api.DocumentSummary;
import dev.aegis.documents.application.api.ProposeClassification;
import dev.aegis.documents.application.ports.CatalogLock;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.DocumentRepository;
import dev.aegis.documents.domain.catalog.CategoryId;
import dev.aegis.documents.domain.catalog.CategoryRepository;
import dev.aegis.documents.domain.catalog.CategoryTree;
import dev.aegis.documents.domain.catalog.Label;
import dev.aegis.documents.domain.catalog.LabelId;
import dev.aegis.documents.domain.catalog.LabelRepository;
import dev.aegis.documents.domain.catalog.LabelVocabulary;
import dev.aegis.documents.domain.classification.ClassificationRepository;
import dev.aegis.documents.domain.classification.Confidence;
import dev.aegis.documents.domain.classification.DocumentClassification;
import dev.aegis.documents.domain.classification.LabelRef;
import dev.aegis.documents.domain.classification.Proposal;
import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.HouseholdAccess;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Folder placement, manual labels, suggestion review and automatic proposals; wrapped in a transaction. */
public class ClassificationService implements ClassificationCommands, ProposeClassification {
    public static final int MAX_BULK_PLACEMENTS = 200;

    private final ClassificationRepository classifications;
    private final DocumentRepository documents;
    private final CategoryRepository categories;
    private final LabelRepository labels;
    private final CatalogLock lock;
    private final DocumentPresenter presenter;
    private final HouseholdAccess access;
    private final Clock clock;

    public ClassificationService(ClassificationRepository classifications, DocumentRepository documents,
            CategoryRepository categories, LabelRepository labels, CatalogLock lock, DocumentPresenter presenter,
            HouseholdAccess access, Clock clock) {
        this.classifications = classifications;
        this.documents = documents;
        this.categories = categories;
        this.labels = labels;
        this.lock = lock;
        this.presenter = presenter;
        this.access = access;
        this.clock = clock;
    }

    @Override
    public DocumentSummary place(Actor actor, DocumentId id, UUID categoryId, long expectedVersion) {
        access.requireMember(actor);
        lock.shared();
        Document document = requireDocument(id);
        CategoryId target = folder(categoryId);
        DocumentClassification classification = loadVersion(id, expectedVersion);
        if (classification.placeIn(target, actor.memberId(), clock.instant())) {
            classifications.save(classification);
        }
        return presenter.summarize(document);
    }

    @Override
    public int placeAll(Actor actor, List<Placement> placements, UUID categoryId) {
        access.requireMember(actor);
        if (placements.size() > MAX_BULK_PLACEMENTS) {
            throw new InvalidInputException("classification.bulk.tooLarge",
                    "At most " + MAX_BULK_PLACEMENTS + " documents can be moved at once");
        }
        lock.shared();
        CategoryId target = folder(categoryId);
        Instant now = clock.instant();
        int moved = 0;
        for (Placement placement : placements) {
            requireDocument(placement.documentId());
            DocumentClassification classification = loadVersion(placement.documentId(),
                    placement.expectedVersion());
            if (classification.placeIn(target, actor.memberId(), now)) {
                classifications.save(classification);
                moved++;
            }
        }
        return moved;
    }

    @Override
    public DocumentSummary setLabels(Actor actor, DocumentId id, List<UUID> labelIds, long expectedVersion) {
        access.requireMember(actor);
        lock.shared();
        Document document = requireDocument(id);
        LabelVocabulary vocabulary = labels.load();
        DocumentClassification classification = loadVersion(id, expectedVersion);
        Set<LabelId> current = classification.effectiveLabelIds();
        Set<LabelRef> refs = new LinkedHashSet<>();
        for (UUID raw : labelIds == null ? List.<UUID>of() : labelIds) {
            Label label = vocabulary.resolve(new LabelId(raw));
            if (!label.acceptsAssignments() && !current.contains(label.id())) {
                throw new InvalidInputException("label.retired", "Retired labels cannot be assigned",
                        java.util.Map.of("labelId", label.id().toString()));
            }
            refs.add(new LabelRef(label.id(), label.facet()));
        }
        classification.setLabels(refs, actor.memberId(), clock.instant());
        classifications.save(classification);
        return presenter.summarize(document);
    }

    @Override
    public DocumentSummary acceptSuggestion(Actor actor, DocumentId id, UUID labelId, long expectedVersion) {
        access.requireMember(actor);
        lock.shared();
        Document document = requireDocument(id);
        DocumentClassification classification = loadVersion(id, expectedVersion);
        classification.accept(new LabelId(labelId), actor.memberId(), clock.instant());
        classifications.save(classification);
        return presenter.summarize(document);
    }

    @Override
    public DocumentSummary rejectSuggestion(Actor actor, DocumentId id, UUID labelId, long expectedVersion) {
        access.requireMember(actor);
        lock.shared();
        Document document = requireDocument(id);
        DocumentClassification classification = loadVersion(id, expectedVersion);
        classification.reject(new LabelId(labelId), actor.memberId(), clock.instant());
        classifications.save(classification);
        return presenter.summarize(document);
    }

    @Override
    public ProposalOutcome propose(Actor actor, ProposalCommand command) {
        access.requireMember(actor);
        lock.shared();
        requireDocument(command.documentId());
        LabelVocabulary vocabulary = labels.load();
        CategoryTree tree = categories.load();
        DocumentClassification classification = classifications.findById(command.documentId())
                .orElseGet(() -> DocumentClassification.unsorted(command.documentId()));
        List<Proposal> proposals = new ArrayList<>();
        int unusable = 0;
        for (LabelProposal proposal : command.proposals()) {
            var label = vocabulary.find(new LabelId(proposal.labelId())).map(l -> vocabulary.resolve(l.id()));
            if (label.isEmpty() || !label.get().acceptsAssignments()) {
                unusable++;
                continue;
            }
            proposals.add(new Proposal(new LabelRef(label.get().id(), label.get().facet()),
                    proposal.confidence() == null ? null : new Confidence(proposal.confidence()), proposal.apply()));
        }
        boolean folderImpliesType = tree.implications(classification.categoryId().orElse(null),
                vocabulary::facetOf).labels().keySet().stream()
                .anyMatch(label -> vocabulary.facetOf(label).singleValued());
        DocumentClassification.ProposalResult result = classification.propose(proposals, command.analyzedVersion(),
                command.sourceRef(), folderImpliesType, clock.instant());
        if (result.applied() + result.suggested() > 0) {
            classifications.save(classification);
        }
        long version = classifications.findById(command.documentId()).map(DocumentClassification::version)
                .orElse(classification.version());
        return new ProposalOutcome(result.applied(), result.suggested(), result.skipped() + unusable, version);
    }

    private CategoryId folder(UUID categoryId) {
        if (categoryId == null) {
            return null;
        }
        return categories.load().require(new CategoryId(categoryId)).id();
    }

    private Document requireDocument(DocumentId id) {
        return documents.findById(id).orElseThrow(() -> new NotFoundException("document", id.toString()));
    }

    private DocumentClassification loadVersion(DocumentId id, long expectedVersion) {
        DocumentClassification classification = classifications.findById(id)
                .orElseGet(() -> DocumentClassification.unsorted(id));
        CatalogViews.requireVersion("documentClassification", id, classification.version(), expectedVersion);
        return classification;
    }
}
