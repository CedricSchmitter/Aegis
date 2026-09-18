package dev.aegis.documents.application;

import dev.aegis.documents.application.api.CategoryCommands;
import dev.aegis.documents.application.api.CategoryView;
import dev.aegis.documents.application.api.SubjectView;
import dev.aegis.documents.application.ports.CatalogLock;
import dev.aegis.documents.application.ports.DocumentCatalog;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.DocumentRepository;
import dev.aegis.documents.domain.Subject;
import dev.aegis.documents.domain.catalog.Category;
import dev.aegis.documents.domain.catalog.CategoryId;
import dev.aegis.documents.domain.catalog.CategoryRepository;
import dev.aegis.documents.domain.catalog.CategoryTree;
import dev.aegis.documents.domain.catalog.Label;
import dev.aegis.documents.domain.catalog.LabelId;
import dev.aegis.documents.domain.catalog.LabelRepository;
import dev.aegis.documents.domain.catalog.LabelVocabulary;
import dev.aegis.documents.domain.classification.ClassificationRepository;
import dev.aegis.documents.domain.classification.DocumentClassification;
import dev.aegis.documents.domain.classification.LabelRef;
import dev.aegis.shared.Actor;
import dev.aegis.shared.HouseholdAccess;
import dev.aegis.shared.InvalidInputException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Folder tree use cases. Structural changes run under the exclusive catalog lock so that concurrent moves cannot
 * form a cycle. Deleting a folder moves its content up and keeps labels and subjects the documents would lose.
 */
public class CategoryService implements CategoryCommands {
    private final CategoryRepository categories;
    private final LabelRepository labels;
    private final ClassificationRepository classifications;
    private final DocumentRepository documents;
    private final DocumentCatalog catalog;
    private final CatalogLock lock;
    private final HouseholdAccess access;
    private final Clock clock;

    public CategoryService(CategoryRepository categories, LabelRepository labels,
            ClassificationRepository classifications, DocumentRepository documents, DocumentCatalog catalog,
            CatalogLock lock, HouseholdAccess access, Clock clock) {
        this.categories = categories;
        this.labels = labels;
        this.classifications = classifications;
        this.documents = documents;
        this.catalog = catalog;
        this.lock = lock;
        this.access = access;
        this.clock = clock;
    }

    @Override
    public CategoryView create(Actor actor, CreateCategory command) {
        access.requireMember(actor);
        lock.exclusive();
        CategoryTree tree = categories.load();
        CategoryId id = command.id() == null ? CategoryId.random() : new CategoryId(command.id());
        CategoryId parent = command.parentId() == null ? null : new CategoryId(command.parentId());
        var existing = tree.find(id);
        if (existing.isPresent()) {
            // A retried create with the same client id must not produce a second folder.
            Category folder = existing.get();
            if (!Objects.equals(folder.parentId().orElse(null), parent)
                    || !folder.nameKeyEquals(command.name())) {
                throw new InvalidInputException("category.id.taken", "Folder id already exists");
            }
            return view(folder);
        }
        Category created = tree.create(id, parent, command.name(), actor.memberId(), clock.instant());
        save(tree);
        return view(reload(created.id()));
    }

    @Override
    public CategoryView rename(Actor actor, UUID id, String name, long expectedVersion) {
        access.requireMember(actor);
        lock.exclusive();
        CategoryTree tree = categories.load();
        tree.rename(new CategoryId(id), name, expectedVersion, clock.instant());
        save(tree);
        return view(reload(new CategoryId(id)));
    }

    @Override
    public CategoryView move(Actor actor, UUID id, UUID parentId, long expectedVersion) {
        access.requireMember(actor);
        lock.exclusive();
        CategoryTree tree = categories.load();
        tree.move(new CategoryId(id), parentId == null ? null : new CategoryId(parentId), expectedVersion,
                clock.instant());
        save(tree);
        return view(reload(new CategoryId(id)));
    }

    @Override
    public CategoryView changeImplications(Actor actor, UUID id, List<UUID> labelIds, List<SubjectView> subjects,
            long expectedVersion) {
        access.requireMember(actor);
        lock.exclusive();
        CategoryTree tree = categories.load();
        LabelVocabulary vocabulary = labels.load();
        CategoryId categoryId = new CategoryId(id);
        Set<LabelId> current = tree.require(categoryId).impliedLabels();
        Set<LabelId> wanted = new LinkedHashSet<>();
        for (UUID raw : labelIds == null ? List.<UUID>of() : labelIds) {
            Label label = vocabulary.resolve(new LabelId(raw));
            if (!label.acceptsAssignments() && !current.contains(label.id())) {
                throw new InvalidInputException("label.retired", "Retired labels cannot be mapped to a folder",
                        Map.of("labelId", label.id().toString()));
            }
            wanted.add(label.id());
        }
        tree.changeImplications(categoryId, wanted, Subjects.fromViews(subjects), vocabulary::facetOf,
                expectedVersion, clock.instant());
        save(tree);
        return view(reload(categoryId));
    }

    @Override
    public DeletionResult delete(Actor actor, UUID id, long expectedVersion) {
        access.requireMember(actor);
        lock.exclusive();
        CategoryTree tree = categories.load();
        LabelVocabulary vocabulary = labels.load();
        Instant now = clock.instant();
        CategoryId target = new CategoryId(id);
        Set<CategoryId> subtree = tree.subtree(target);
        Map<CategoryId, CategoryTree.Implications> before = new HashMap<>();
        subtree.forEach(folder -> before.put(folder, tree.implications(folder, vocabulary::facetOf)));

        CategoryTree.Removal removal = tree.remove(target, expectedVersion, now);
        CategoryId contentsTarget = removal.contentsTarget().orElse(null);
        int documentsMoved = 0;
        int labelsKept = 0;
        int subjectsKept = 0;
        for (DocumentClassification classification : classifications.findByCategories(subtree)) {
            CategoryId placement = classification.categoryId().orElse(null);
            CategoryId newPlacement = target.equals(placement) ? contentsTarget : placement;
            CategoryTree.Implications lost = before.getOrDefault(placement, CategoryTree.Implications.NONE);
            CategoryTree.Implications kept = tree.implications(newPlacement, vocabulary::facetOf);
            boolean changed = false;
            for (LabelId label : lost.labels().keySet()) {
                if (kept.labels().containsKey(label)) {
                    continue;
                }
                Label resolved = vocabulary.resolve(label);
                if (classification.keep(new LabelRef(resolved.id(), resolved.facet()), actor.memberId(), now)) {
                    labelsKept++;
                    changed = true;
                }
            }
            if (!Objects.equals(placement, newPlacement)) {
                classification.placeIn(newPlacement, actor.memberId(), now);
                documentsMoved++;
                changed = true;
            }
            if (changed) {
                classifications.save(classification);
            }
            subjectsKept += keepSubjects(classification, lost, kept);
        }
        save(tree);
        return new DeletionResult(id, contentsTarget == null ? null : contentsTarget.value(), documentsMoved,
                removal.movedChildren().size(), labelsKept, subjectsKept);
    }

    private int keepSubjects(DocumentClassification classification, CategoryTree.Implications lost,
            CategoryTree.Implications kept) {
        Set<Subject> missing = new LinkedHashSet<>(lost.subjects().keySet());
        missing.removeAll(kept.subjects().keySet());
        if (missing.isEmpty()) {
            return 0;
        }
        Document document = documents.findById(classification.documentId()).orElse(null);
        if (document == null) {
            return 0;
        }
        Set<Subject> merged = new LinkedHashSet<>(document.subjects());
        if (!merged.addAll(missing)) {
            return 0;
        }
        document.changeSubjects(merged);
        documents.save(document);
        return 1;
    }

    private void save(CategoryTree tree) {
        categories.save(tree, tree.effects(labels.load()::facetOf));
    }

    private Category reload(CategoryId id) {
        return categories.load().require(id);
    }

    private CategoryView view(Category category) {
        return CatalogViews.category(category, catalog.documentsPerCategory());
    }
}
