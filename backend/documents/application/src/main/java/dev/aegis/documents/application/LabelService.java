package dev.aegis.documents.application;

import dev.aegis.documents.application.api.LabelCommands;
import dev.aegis.documents.application.api.LabelView;
import dev.aegis.documents.application.ports.CatalogLock;
import dev.aegis.documents.application.ports.DocumentCatalog;
import dev.aegis.documents.domain.catalog.CategoryRepository;
import dev.aegis.documents.domain.catalog.CategoryTree;
import dev.aegis.documents.domain.catalog.Facet;
import dev.aegis.documents.domain.catalog.Label;
import dev.aegis.documents.domain.catalog.LabelId;
import dev.aegis.documents.domain.catalog.LabelRepository;
import dev.aegis.documents.domain.catalog.LabelStatus;
import dev.aegis.documents.domain.catalog.LabelVocabulary;
import dev.aegis.documents.domain.classification.ClassificationRepository;
import dev.aegis.documents.domain.classification.DocumentClassification;
import dev.aegis.documents.domain.classification.ReviewState;
import dev.aegis.shared.Actor;
import dev.aegis.shared.HouseholdAccess;
import dev.aegis.shared.InvalidInputException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Vocabulary use cases under the exclusive catalog lock. Merging and converting rewrite assignments and folder
 * mappings so that no reference points at a stale entry; nothing ever deletes a document.
 */
public class LabelService implements LabelCommands {
    private final LabelRepository labels;
    private final CategoryRepository categories;
    private final ClassificationRepository classifications;
    private final DocumentCatalog catalog;
    private final CatalogLock lock;
    private final HouseholdAccess access;
    private final Clock clock;

    public LabelService(LabelRepository labels, CategoryRepository categories,
            ClassificationRepository classifications, DocumentCatalog catalog, CatalogLock lock,
            HouseholdAccess access, Clock clock) {
        this.labels = labels;
        this.categories = categories;
        this.classifications = classifications;
        this.catalog = catalog;
        this.lock = lock;
        this.access = access;
        this.clock = clock;
    }

    @Override
    public LabelView create(Actor actor, UUID id, String facet, String name) {
        access.requireMember(actor);
        lock.exclusive();
        LabelVocabulary vocabulary = labels.load();
        Facet parsed = Facet.parse(facet);
        LabelId labelId = id == null ? LabelId.random() : new LabelId(id);
        var existing = vocabulary.find(labelId);
        if (existing.isPresent()) {
            // A retried create with the same client id must not produce a second entry.
            Label label = existing.get();
            if (label.facet() != parsed || !label.matchKeys().contains(
                    dev.aegis.documents.domain.catalog.CatalogNames.matchKey(name))) {
                throw new InvalidInputException("label.id.taken", "Label id already exists");
            }
            return view(label, vocabulary);
        }
        Label label = Label.create(labelId, parsed, name, actor.memberId(), clock.instant());
        vocabulary.requireUniqueKeys(label);
        labels.save(label);
        return view(labels.findById(labelId).orElseThrow(), labels.load());
    }

    @Override
    public LabelView rename(Actor actor, UUID id, String name, long expectedVersion) {
        return change(actor, id, expectedVersion, (label, vocabulary) -> {
            label.rename(name, clock.instant());
            vocabulary.requireUniqueKeys(label);
        });
    }

    @Override
    public LabelView replaceAliases(Actor actor, UUID id, List<String> aliases, long expectedVersion) {
        return change(actor, id, expectedVersion, (label, vocabulary) -> {
            label.replaceAliases(aliases, clock.instant());
            vocabulary.requireUniqueKeys(label);
        });
    }

    @Override
    public LabelView retire(Actor actor, UUID id, long expectedVersion) {
        return change(actor, id, expectedVersion, (label, vocabulary) -> label.retire(clock.instant()));
    }

    @Override
    public LabelView reactivate(Actor actor, UUID id, long expectedVersion) {
        return change(actor, id, expectedVersion, (label, vocabulary) -> {
            label.reactivate(clock.instant());
            vocabulary.requireUniqueKeys(label);
        });
    }

    @Override
    public MergeResult merge(Actor actor, UUID sourceId, UUID targetId, long expectedVersion) {
        access.requireMember(actor);
        lock.exclusive();
        Instant now = clock.instant();
        LabelVocabulary vocabulary = labels.load();
        Label source = vocabulary.require(new LabelId(sourceId));
        Label target = vocabulary.require(new LabelId(targetId));
        CatalogViews.requireVersion("label", sourceId, source.version(), expectedVersion);

        source.mergeInto(target, now);
        vocabulary.requireUniqueKeys(target);
        labels.save(source);
        labels.save(target);

        int documentsUpdated = 0;
        for (DocumentClassification classification : classifications.findByLabel(source.id())) {
            if (classification.replaceLabel(source.id(), target.id())) {
                classifications.save(classification);
                documentsUpdated++;
            }
        }
        CategoryTree tree = categories.load();
        tree.replaceImpliedLabel(source.id(), target.id(), now);
        int foldersUpdated = tree.changes().upserts().size();
        categories.save(tree, tree.effects(labels.load()::facetOf));
        LabelVocabulary reloaded = labels.load();
        return new MergeResult(view(reloaded.require(target.id()), reloaded), documentsUpdated, foldersUpdated);
    }

    @Override
    public LabelView convert(Actor actor, UUID id, String facet, long expectedVersion) {
        access.requireMember(actor);
        lock.exclusive();
        LabelVocabulary vocabulary = labels.load();
        Label label = vocabulary.require(new LabelId(id));
        CatalogViews.requireVersion("label", id, label.version(), expectedVersion);
        Facet parsed = Facet.parse(facet);
        label.convertTo(parsed, clock.instant());
        vocabulary.requireUniqueKeys(label);
        labels.save(label);
        for (DocumentClassification classification : classifications.findByLabel(label.id())) {
            classification.refacet(label.id(), parsed);
            classifications.save(classification);
        }
        CategoryTree tree = categories.load();
        categories.save(tree, tree.effects(labels.load()::facetOf));
        LabelVocabulary reloaded = labels.load();
        return view(reloaded.require(label.id()), reloaded);
    }

    @Override
    public void delete(Actor actor, UUID id, long expectedVersion) {
        access.requireMember(actor);
        lock.exclusive();
        LabelVocabulary vocabulary = labels.load();
        LabelId labelId = new LabelId(id);
        Label label = vocabulary.require(labelId);
        CatalogViews.requireVersion("label", id, label.version(), expectedVersion);
        if (label.isBuiltin()) {
            throw new InvalidInputException("label.builtin", "Built-in labels can only be retired");
        }
        boolean redirected = vocabulary.all().stream()
                .anyMatch(other -> other.mergedInto().map(labelId::equals).orElse(false));
        boolean mapped = CatalogViews.folderCounts(categories.load()).containsKey(labelId);
        List<DocumentClassification> assigned = classifications.findByLabel(labelId);
        boolean inUse = assigned.stream().anyMatch(classification -> classification.assignment(labelId)
                .map(a -> a.effective() || a.state() == ReviewState.SUGGESTED).orElse(false));
        if (redirected || mapped || inUse) {
            throw new InvalidInputException("label.inUse", "The label is still referenced",
                    Map.of("documents", String.valueOf(assigned.size())));
        }
        for (DocumentClassification classification : assigned) {
            if (classification.forget(labelId)) {
                classifications.save(classification);
            }
        }
        labels.delete(labelId);
    }

    private LabelView change(Actor actor, UUID id, long expectedVersion, LabelChange change) {
        access.requireMember(actor);
        lock.exclusive();
        LabelVocabulary vocabulary = labels.load();
        Label label = vocabulary.require(new LabelId(id));
        CatalogViews.requireVersion("label", id, label.version(), expectedVersion);
        change.apply(label, vocabulary);
        labels.save(label);
        LabelVocabulary reloaded = labels.load();
        return view(reloaded.require(label.id()), reloaded);
    }

    private LabelView view(Label label, LabelVocabulary vocabulary) {
        if (label.status() == LabelStatus.MERGED) {
            return CatalogViews.label(label, Map.of(), Map.of());
        }
        return CatalogViews.label(label, catalog.labelUsage(), CatalogViews.folderCounts(categories.load()));
    }

    private interface LabelChange {
        void apply(Label label, LabelVocabulary vocabulary);
    }
}
