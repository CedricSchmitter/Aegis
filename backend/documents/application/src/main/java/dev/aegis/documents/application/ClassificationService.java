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
import dev.aegis.documents.domain.analysis.ClassificationPolicy;
import dev.aegis.documents.domain.classification.ClassificationRepository;
import dev.aegis.documents.domain.classification.Confidence;
import dev.aegis.documents.domain.classification.DocumentClassification;
import dev.aegis.documents.domain.classification.LabelRef;
import dev.aegis.documents.domain.classification.Proposal;
import dev.aegis.documents.domain.placement.PlacementSuggestion;
import dev.aegis.documents.domain.placement.PlacementSuggestionRepository;
import dev.aegis.documents.domain.placement.PlacementTarget;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Folder placement, manual labels, suggestion review and automatic proposals; wrapped in a transaction. */
public class ClassificationService implements ClassificationCommands, ProposeClassification {
    public static final int MAX_BULK_PLACEMENTS = 200;

    private final ClassificationRepository classifications;
    private final DocumentRepository documents;
    private final CategoryRepository categories;
    private final LabelRepository labels;
    private final PlacementSuggestionRepository placements;
    private final CatalogLock lock;
    private final DocumentPresenter presenter;
    private final HouseholdAccess access;
    private final Clock clock;

    public ClassificationService(ClassificationRepository classifications, DocumentRepository documents,
            CategoryRepository categories, LabelRepository labels, PlacementSuggestionRepository placements,
            CatalogLock lock, DocumentPresenter presenter, HouseholdAccess access, Clock clock) {
        this.classifications = classifications;
        this.documents = documents;
        this.categories = categories;
        this.labels = labels;
        this.placements = placements;
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
        classification.placeIn(target, actor.memberId(), clock.instant());
        classifications.save(classification);
        // The member decided where this document belongs; a pending proposal is obsolete.
        placements.delete(id);
        return presenter.summarize(document);
    }

    @Override
    public int placeAll(Actor actor, List<Placement> moves, UUID categoryId) {
        access.requireMember(actor);
        if (moves.size() > MAX_BULK_PLACEMENTS) {
            throw new InvalidInputException("classification.bulk.tooLarge",
                    "At most " + MAX_BULK_PLACEMENTS + " documents can be moved at once");
        }
        lock.shared();
        CategoryId target = folder(categoryId);
        Instant now = clock.instant();
        int moved = 0;
        for (Placement move : moves) {
            requireDocument(move.documentId());
            DocumentClassification classification = loadVersion(move.documentId(), move.expectedVersion());
            if (classification.placeIn(target, actor.memberId(), now)) {
                moved++;
            }
            classifications.save(classification);
            placements.delete(move.documentId());
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
        classification.requireVersion(command.analyzedVersion());
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
        Instant now = clock.instant();
        // The placement is decided first: a folder the document is moved into may already imply its document type.
        PlacementResult placement = decidePlacement(command, classification, tree, vocabulary, proposals, now);
        boolean folderImpliesType = tree.implications(classification.categoryId().orElse(null),
                vocabulary::facetOf).labels().keySet().stream()
                .anyMatch(label -> vocabulary.facetOf(label).singleValued());
        DocumentClassification.ProposalResult result = classification.propose(proposals, command.analyzedVersion(),
                command.sourceRef(), folderImpliesType, now);
        if (result.applied() + result.suggested() > 0 || placement != null && placement.applied()) {
            classifications.save(classification);
        }
        long version = classifications.findById(command.documentId()).map(DocumentClassification::version)
                .orElse(classification.version());
        return new ProposalOutcome(result.applied(), result.suggested(), result.skipped() + unusable, version,
                placement);
    }

    /**
     * Resolves the proposed placement against the current tree and either files the document (existing folder,
     * policy satisfied) or records a reviewable suggestion. A folder that disappeared during the run falls back to
     * the proposed path, a path that meanwhile exists resolves to that folder, and a target the member already
     * rejected for this document is dropped.
     */
    private PlacementResult decidePlacement(ProposalCommand command, DocumentClassification classification,
            CategoryTree tree, LabelVocabulary vocabulary, List<Proposal> labelProposals, Instant now) {
        DocumentId id = command.documentId();
        PlacementProposal proposal = command.placement();
        if (proposal == null) {
            placements.delete(id);
            return null;
        }
        Optional<PlacementTarget> resolved = resolve(proposal, tree);
        // A document a member filed themselves stays where it is and is not proposed about again; one the member
        // moved back to Unsorted may still get a different proposal, only never a target they rejected.
        boolean memberFiled = classification.placement().byMember() && !classification.isUnsorted();
        if (resolved.isEmpty() || memberFiled || placements.rejectedKeys(id).contains(resolved.get().key())
                || sameFolder(classification, resolved.get())) {
            placements.delete(id);
            return null;
        }
        PlacementTarget target = resolved.get();
        Double confidence = proposal.confidence();
        if (target.isExisting() && applies(proposal, classification, target.folder(), tree, vocabulary,
                labelProposals)
                && classification.placeAutomatically(target.folder(),
                        confidence == null ? null : new Confidence(confidence), command.sourceRef(), now)) {
            placements.delete(id);
            return new PlacementResult(target.folder().value(), null, List.of(), target.folder().value(),
                    target.key(), confidence, proposal.evidence());
        }
        placements.save(new PlacementSuggestion(id, proposal.generation(), target, confidence, proposal.evidence(),
                command.sourceRef(), proposal.model(), now));
        return new PlacementResult(target.folderId().map(CategoryId::value).orElse(null),
                target.anchorId().map(CategoryId::value).orElse(null), target.newPath(), null, target.key(),
                confidence, proposal.evidence());
    }

    private static boolean sameFolder(DocumentClassification classification, PlacementTarget target) {
        return target.isExisting() && classification.categoryId().map(target.folder()::equals).orElse(false);
    }

    /**
     * Deterministic resolution (ADR-0005): an existing folder wins by id, then by normalized sibling name along
     * the proposed path, so a folder created while the run was working is reused instead of a near-duplicate.
     */
    private static Optional<PlacementTarget> resolve(PlacementProposal proposal, CategoryTree tree) {
        if (proposal.folderId() != null) {
            Optional<PlacementTarget> folder = tree.find(new CategoryId(proposal.folderId()))
                    .map(found -> PlacementTarget.existing(found.id()));
            if (folder.isPresent()) {
                return folder;
            }
        }
        if (proposal.newPath().isEmpty()) {
            return Optional.empty();
        }
        CategoryId anchor = null;
        if (proposal.anchorFolderId() != null) {
            // The path was meant for a folder that no longer exists; re-rooting it could file the document
            // somewhere else entirely, so the proposal ends here.
            anchor = tree.find(new CategoryId(proposal.anchorFolderId())).map(found -> found.id()).orElse(null);
            if (anchor == null) {
                return Optional.empty();
            }
        }
        CategoryTree.PathResolution resolution = tree.resolve(anchor, proposal.newPath());
        if (resolution.complete()) {
            return Optional.of(PlacementTarget.existing(resolution.deepest()));
        }
        int depth = resolution.deepest() == null ? 0 : tree.depth(resolution.deepest());
        if (depth + resolution.missing().size() > CategoryTree.MAX_DEPTH) {
            return Optional.empty();
        }
        try {
            return Optional.of(PlacementTarget.path(resolution.deepest(), resolution.missing()));
        } catch (InvalidInputException unusable) {
            // Unusable folder names (blank, too long, control characters) end the proposal here.
            return Optional.empty();
        }
    }

    /** The documented rule for filing without review; the folder's implied labels must match the document. */
    private static boolean applies(PlacementProposal proposal, DocumentClassification classification,
            CategoryId target, CategoryTree tree, LabelVocabulary vocabulary, List<Proposal> labelProposals) {
        Set<LabelId> effective = new LinkedHashSet<>(classification.effectiveLabelIds());
        labelProposals.stream().filter(Proposal::apply).forEach(p -> effective.add(p.label().id()));
        return ClassificationPolicy.placesAutomatically(proposal.doubtful(), proposal.confidence(),
                proposal.evidenced(), classification.isUnsorted(), classification.placement().byMember(),
                FolderCatalogs.impliedLabelIds(tree, target, vocabulary), effective);
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
