package dev.aegis.documents.domain.classification;

import dev.aegis.documents.domain.catalog.CategoryId;
import dev.aegis.documents.domain.catalog.Facet;
import dev.aegis.documents.domain.catalog.LabelId;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.StaleVersionException;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Folder placement and own label assignments of one document. Kept apart from the document aggregate so that
 * archival progress never invalidates a member's pending classification edit. Invariants: single placement, one
 * assignment per label, at most one effective document type, and human decisions are never overridden by
 * automatic proposals.
 *
 * <p>The placement carries its own provenance (ADR-0005): who or which run filed the document, and whether a
 * member has ever placed or moved it. That flag is sticky, including a move back to Unsorted, so later automatic
 * runs leave the document where the member put it.
 */
public final class DocumentClassification {
    public static final int MAX_ASSIGNMENTS = 100;

    /** Counts of an automatic proposal run. */
    public record ProposalResult(int applied, int suggested, int skipped) {
    }

    /**
     * Provenance of the current folder placement. {@code origin} is {@code null} while the document has never been
     * placed; {@code byMember} stays true once a member has placed or moved the document, including a move back to
     * Unsorted.
     */
    public record Placement(AssignmentOrigin origin, Confidence confidence, String sourceRef, MemberId by,
            Instant at, boolean byMember) {
        public static final Placement NONE = new Placement(null, null, null, null, null, false);

        public boolean automatic() {
            return origin == AssignmentOrigin.AUTOMATIC;
        }
    }

    private final DocumentId documentId;
    private CategoryId categoryId;
    private Placement placement;
    private final Map<LabelId, LabelAssignment> assignments = new LinkedHashMap<>();
    private MemberId updatedBy;
    private Instant updatedAt;
    private final long version;

    private DocumentClassification(DocumentId documentId, CategoryId categoryId, Placement placement,
            Collection<LabelAssignment> assignments, MemberId updatedBy, Instant updatedAt, long version) {
        this.documentId = Objects.requireNonNull(documentId);
        this.categoryId = categoryId;
        this.placement = placement == null ? Placement.NONE : placement;
        assignments.forEach(a -> this.assignments.put(a.labelId(), a));
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    /** A new document starts in Unsorted without labels. */
    public static DocumentClassification unsorted(DocumentId documentId) {
        return new DocumentClassification(documentId, null, Placement.NONE, List.of(), null, null, 0);
    }

    public static DocumentClassification rehydrate(DocumentId documentId, CategoryId categoryId, Placement placement,
            Collection<LabelAssignment> assignments, MemberId updatedBy, Instant updatedAt, long version) {
        return new DocumentClassification(documentId, categoryId, placement, assignments, updatedBy, updatedAt,
                version);
    }

    /**
     * Places the document in a folder ({@code null} = Unsorted) as a member's decision. Returns whether the folder
     * changed; the member decision is recorded even when the folder stays the same.
     */
    public boolean placeIn(CategoryId category, MemberId by, Instant now) {
        Objects.requireNonNull(by, "by");
        boolean moved = !Objects.equals(categoryId, category);
        if (!moved && placement.byMember() && !placement.automatic()) {
            return false;
        }
        categoryId = category;
        placement = new Placement(category == null ? null : AssignmentOrigin.MANUAL, null, null, by, now, true);
        touch(by, now);
        return moved;
    }

    /**
     * Files the document automatically into an existing folder. Refused (returns {@code false}) unless the document
     * is still in Unsorted and no member has ever placed or moved it, so a member's decision always wins.
     */
    public boolean placeAutomatically(CategoryId category, Confidence confidence, String sourceRef, Instant now) {
        Objects.requireNonNull(category, "category");
        if (categoryId != null || placement.byMember()) {
            return false;
        }
        categoryId = category;
        placement = new Placement(AssignmentOrigin.AUTOMATIC, confidence, sourceRef, null, now, false);
        updatedAt = now;
        return true;
    }

    /**
     * Takes an automatic placement back: the document returns to Unsorted and counts as decided by the member, so
     * later runs do not file it again.
     */
    public void revertPlacement(MemberId by, Instant now) {
        if (!placement.automatic()) {
            throw new InvalidInputException("placement.notAutomatic", "The document was not filed automatically");
        }
        categoryId = null;
        placement = new Placement(null, null, null, by, now, true);
        touch(by, now);
    }

    /**
     * Moves the document because its folder disappeared. Structural moves keep the placement provenance and do not
     * count as a member's decision about this document.
     */
    public boolean relocate(CategoryId category, Instant now) {
        if (Objects.equals(categoryId, category)) {
            return false;
        }
        categoryId = category;
        placement = category == null
                ? new Placement(null, null, null, placement.by(), now, placement.byMember())
                : new Placement(placement.origin(), placement.confidence(), placement.sourceRef(), placement.by(),
                        now, placement.byMember());
        updatedAt = now;
        return true;
    }

    /** Refuses a write whose analyzed state is no longer the current one. */
    public void requireVersion(long analyzedVersion) {
        if (analyzedVersion != version) {
            throw new StaleVersionException("documentClassification", documentId.toString(), analyzedVersion,
                    version);
        }
    }

    /**
     * Makes {@code desired} the document's own effective labels. Removed automatic values are remembered as
     * rejected; removed manual values disappear. Pending suggestions for other document types are rejected when a
     * type is chosen. Suggestions of other labels stay untouched.
     */
    public void setLabels(Collection<LabelRef> desired, MemberId by, Instant now) {
        Objects.requireNonNull(by);
        Map<LabelId, LabelRef> wanted = new LinkedHashMap<>();
        desired.forEach(ref -> wanted.put(ref.id(), ref));
        List<LabelRef> types = wanted.values().stream().filter(ref -> ref.facet().singleValued()).toList();
        if (types.size() > 1) {
            throw new InvalidInputException("classification.singleType",
                    "A document can have at most one document type");
        }
        boolean changed = false;
        for (LabelAssignment current : List.copyOf(assignments.values())) {
            if (current.effective() && !wanted.containsKey(current.labelId())) {
                withdraw(current, by, now);
                changed = true;
            }
        }
        for (LabelRef ref : wanted.values()) {
            changed |= confirm(ref, by, now);
        }
        if (!types.isEmpty()) {
            changed |= rejectPendingTypesExcept(types.get(0).id(), by, now);
        }
        requireCapacity();
        if (changed) {
            touch(by, now);
        }
    }

    /** Confirms a suggested or automatically applied label; a confirmed type replaces the previous type. */
    public void accept(LabelId label, MemberId by, Instant now) {
        LabelAssignment current = requireAutomatic(label);
        if (current.state() == ReviewState.CONFIRMED) {
            return;
        }
        if (current.facet().singleValued()) {
            for (LabelAssignment other : List.copyOf(assignments.values())) {
                if (!other.labelId().equals(label) && other.facet().singleValued() && other.effective()) {
                    withdraw(other, by, now);
                }
            }
            rejectPendingTypesExcept(label, by, now);
        }
        assignments.put(label, current.reviewed(ReviewState.CONFIRMED, by, now));
        touch(by, now);
    }

    /** Rejects a suggestion or removes an automatically applied label; the decision is remembered. */
    public void reject(LabelId label, MemberId by, Instant now) {
        LabelAssignment current = requireAutomatic(label);
        if (current.state() == ReviewState.REJECTED) {
            return;
        }
        assignments.put(label, current.reviewed(ReviewState.REJECTED, by, now));
        touch(by, now);
    }

    /**
     * Records automatic proposals for the classification version the classifier analyzed. Labels a member already
     * decided on, and labels already proposed, are skipped; a document type is only applied while the document has
     * neither an own effective type nor a type implied by its folder ({@code folderImpliesType}).
     *
     * @throws StaleVersionException when the document changed after the analysis
     */
    public ProposalResult propose(Collection<Proposal> proposals, long analyzedVersion, String sourceRef,
            boolean folderImpliesType, Instant now) {
        if (analyzedVersion != version) {
            throw new StaleVersionException("documentClassification", documentId.toString(), analyzedVersion,
                    version);
        }
        int applied = 0;
        int suggested = 0;
        int skipped = 0;
        for (Proposal proposal : proposals) {
            LabelRef ref = proposal.label();
            if (assignments.containsKey(ref.id())) {
                skipped++;
                continue;
            }
            boolean typeTaken = folderImpliesType || effectiveType().isPresent();
            boolean apply = proposal.apply() && !(ref.facet().singleValued() && typeTaken);
            ReviewState state = apply ? ReviewState.APPLIED : ReviewState.SUGGESTED;
            assignments.put(ref.id(), new LabelAssignment(ref.id(), ref.facet(), AssignmentOrigin.AUTOMATIC, state,
                    proposal.confidence(), sourceRef, null, now));
            if (apply) {
                applied++;
            } else {
                suggested++;
            }
        }
        requireCapacity();
        if (applied + suggested > 0) {
            updatedAt = now;
        }
        return new ProposalResult(applied, suggested, skipped);
    }

    /**
     * Adds a confirmed label that the document would otherwise lose (for example when its folder is deleted). A
     * document type is only added while the document has no effective type of its own.
     */
    public boolean keep(LabelRef ref, MemberId by, Instant now) {
        LabelAssignment current = assignments.get(ref.id());
        if (current != null && current.effective()) {
            return false;
        }
        if (ref.facet().singleValued() && effectiveType().isPresent()) {
            return false;
        }
        assignments.put(ref.id(), LabelAssignment.manual(ref, by, now));
        requireCapacity();
        touch(by, now);
        return true;
    }

    /** Forgets a remembered rejection; used when the label itself is deleted. */
    public boolean forget(LabelId label) {
        LabelAssignment current = assignments.get(label);
        if (current == null) {
            return false;
        }
        if (current.state() != ReviewState.REJECTED) {
            throw new IllegalStateException("label " + label + " is still in use on " + documentId);
        }
        assignments.remove(label);
        return true;
    }

    /** Moves the assignment of a merged label to its target; the stronger review state survives. */
    public boolean replaceLabel(LabelId from, LabelId to) {
        LabelAssignment source = assignments.remove(from);
        if (source == null) {
            return false;
        }
        LabelAssignment target = assignments.get(to);
        if (target == null || source.state().outranks(target.state())) {
            assignments.put(to, source.relabeled(to));
        }
        return true;
    }

    /** Updates the stored facet after a tag became a canonical label. */
    public void refacet(LabelId label, Facet facet) {
        LabelAssignment current = assignments.get(label);
        if (current != null && current.facet() != facet) {
            if (facet.singleValued()) {
                throw new InvalidInputException("label.convert.unsupported", "Tags cannot become document types");
            }
            assignments.put(label, new LabelAssignment(label, facet, current.origin(), current.state(),
                    current.confidence(), current.sourceRef(), current.decidedBy(), current.decidedAt()));
        }
    }

    /** Restores an exported classification with its placement origin; only while the document is unclassified. */
    public void restore(CategoryId category, Placement restoredPlacement, Collection<LabelAssignment> restored,
            MemberId by, Instant now) {
        if (!isPristine()) {
            throw new IllegalStateException("classification of " + documentId + " is not pristine");
        }
        long types = restored.stream().filter(a -> a.facet().singleValued() && a.effective()).count();
        if (types > 1) {
            throw new InvalidInputException("classification.singleType",
                    "A document can have at most one document type");
        }
        categoryId = category;
        placement = restoredPlacement == null
                ? new Placement(category == null ? null : AssignmentOrigin.MANUAL, null, null, by, now,
                        category != null)
                : restoredPlacement;
        restored.forEach(a -> assignments.put(a.labelId(), a));
        requireCapacity();
        touch(by, now);
    }

    public boolean isPristine() {
        return categoryId == null && assignments.isEmpty() && !placement.byMember();
    }

    public Set<LabelId> effectiveLabelIds() {
        Set<LabelId> ids = new LinkedHashSet<>();
        assignments.values().stream().filter(LabelAssignment::effective).forEach(a -> ids.add(a.labelId()));
        return ids;
    }

    public Optional<LabelId> effectiveType() {
        return assignments.values().stream().filter(a -> a.facet().singleValued() && a.effective())
                .map(LabelAssignment::labelId).findFirst();
    }

    public DocumentId documentId() {
        return documentId;
    }

    public Optional<CategoryId> categoryId() {
        return Optional.ofNullable(categoryId);
    }

    public boolean isUnsorted() {
        return categoryId == null;
    }

    public Placement placement() {
        return placement;
    }

    public List<LabelAssignment> assignments() {
        return List.copyOf(assignments.values());
    }

    public Optional<LabelAssignment> assignment(LabelId label) {
        return Optional.ofNullable(assignments.get(label));
    }

    public Optional<MemberId> updatedBy() {
        return Optional.ofNullable(updatedBy);
    }

    public Optional<Instant> updatedAt() {
        return Optional.ofNullable(updatedAt);
    }

    public long version() {
        return version;
    }

    private boolean confirm(LabelRef ref, MemberId by, Instant now) {
        LabelAssignment current = assignments.get(ref.id());
        if (current != null && current.state() == ReviewState.CONFIRMED) {
            return false;
        }
        boolean pendingAutomatic = current != null && current.state() != ReviewState.REJECTED;
        // Accepting a pending automatic value keeps its provenance; anything else is a fresh member decision.
        assignments.put(ref.id(), pendingAutomatic ? current.reviewed(ReviewState.CONFIRMED, by, now)
                : LabelAssignment.manual(ref, by, now));
        return true;
    }

    private void withdraw(LabelAssignment current, MemberId by, Instant now) {
        if (current.origin() == AssignmentOrigin.AUTOMATIC) {
            assignments.put(current.labelId(), current.reviewed(ReviewState.REJECTED, by, now));
        } else {
            assignments.remove(current.labelId());
        }
    }

    private boolean rejectPendingTypesExcept(LabelId chosen, MemberId by, Instant now) {
        boolean changed = false;
        for (LabelAssignment other : List.copyOf(assignments.values())) {
            if (!other.labelId().equals(chosen) && other.facet().singleValued()
                    && other.state() == ReviewState.SUGGESTED) {
                assignments.put(other.labelId(), other.reviewed(ReviewState.REJECTED, by, now));
                changed = true;
            }
        }
        return changed;
    }

    private LabelAssignment requireAutomatic(LabelId label) {
        LabelAssignment current = assignments.get(label);
        if (current == null || current.origin() != AssignmentOrigin.AUTOMATIC) {
            throw new InvalidInputException("classification.suggestion.missing",
                    "The document has no automatic value for this label");
        }
        return current;
    }

    private void requireCapacity() {
        if (assignments.size() > MAX_ASSIGNMENTS) {
            throw new InvalidInputException("classification.tooManyLabels",
                    "A document can carry at most " + MAX_ASSIGNMENTS + " labels");
        }
    }

    private void touch(MemberId by, Instant now) {
        updatedBy = by;
        updatedAt = now;
    }
}
