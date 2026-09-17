package dev.aegis.documents.application;

import dev.aegis.documents.application.api.ImportCatalog;
import dev.aegis.documents.application.ports.CatalogLock;
import dev.aegis.documents.domain.DocumentRepository;
import dev.aegis.documents.domain.catalog.Category;
import dev.aegis.documents.domain.catalog.CategoryId;
import dev.aegis.documents.domain.catalog.CategoryRepository;
import dev.aegis.documents.domain.catalog.CategoryTree;
import dev.aegis.documents.domain.catalog.Facet;
import dev.aegis.documents.domain.catalog.Label;
import dev.aegis.documents.domain.catalog.LabelId;
import dev.aegis.documents.domain.catalog.LabelRepository;
import dev.aegis.documents.domain.catalog.LabelStatus;
import dev.aegis.documents.domain.catalog.LabelVocabulary;
import dev.aegis.documents.domain.classification.AssignmentOrigin;
import dev.aegis.documents.domain.classification.ClassificationRepository;
import dev.aegis.documents.domain.classification.Confidence;
import dev.aegis.documents.domain.classification.DocumentClassification;
import dev.aegis.documents.domain.classification.LabelAssignment;
import dev.aegis.documents.domain.classification.ReviewState;
import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.HouseholdAccess;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Restores catalog and classifications from an export. Local entries always win: equal ids are kept, equal label
 * names and sibling folder names are mapped to the local entry, and only an unclassified document takes over an
 * exported classification. Everything is reported through warnings instead of failing the restore.
 */
public class CatalogImportService implements ImportCatalog {
    private final LabelRepository labels;
    private final CategoryRepository categories;
    private final ClassificationRepository classifications;
    private final DocumentRepository documents;
    private final CatalogLock lock;
    private final HouseholdAccess access;
    private final Clock clock;

    public CatalogImportService(LabelRepository labels, CategoryRepository categories,
            ClassificationRepository classifications, DocumentRepository documents, CatalogLock lock,
            HouseholdAccess access, Clock clock) {
        this.labels = labels;
        this.categories = categories;
        this.classifications = classifications;
        this.documents = documents;
        this.lock = lock;
        this.access = access;
        this.clock = clock;
    }

    @Override
    public CatalogImportResult importCatalog(Actor actor, List<LabelImport> importedLabels,
            List<FolderImport> importedFolders) {
        access.requireMember(actor);
        lock.exclusive();
        Instant now = clock.instant();
        LabelVocabulary vocabulary = labels.load();
        List<String> warnings = new ArrayList<>();
        Map<UUID, UUID> labelIds = new LinkedHashMap<>();
        int labelsCreated = restoreLabels(actor, importedLabels, vocabulary, labelIds, warnings, now);
        RestoredFolders folders = restoreFolders(actor, importedFolders, vocabulary, labelIds, warnings, now);
        return new CatalogImportResult(labelIds, folders.mapping(), labelsCreated, folders.created(), warnings);
    }

    private record RestoredFolders(Map<UUID, UUID> mapping, int created) {
    }

    private int restoreLabels(Actor actor, List<LabelImport> importedLabels, LabelVocabulary vocabulary,
            Map<UUID, UUID> labelIds, List<String> warnings, Instant now) {
        int created = 0;
        List<LabelImport> redirects = new ArrayList<>();
        for (LabelImport imported : importedLabels) {
            if (LabelStatus.MERGED.name().equals(imported.status())) {
                redirects.add(imported);
                continue;
            }
            Facet facet = Facet.parse(imported.facet());
            LabelId id = new LabelId(imported.id());
            Optional<Label> local = vocabulary.find(id);
            if (local.isEmpty() && imported.builtinKey() != null) {
                local = vocabulary.all().stream()
                        .filter(label -> label.builtinKey().map(imported.builtinKey()::equals).orElse(false))
                        .findFirst();
            }
            if (local.isPresent()) {
                labelIds.put(imported.id(), local.get().id().value());
                try {
                    adoptUnmodifiedBuiltin(local.get(), imported, vocabulary, now);
                } catch (InvalidInputException conflict) {
                    // A restore never fails over a name that is already taken locally; the local name stays.
                    warnings.add("kept the local name of built-in label " + local.get().id() + ": "
                            + conflict.code());
                }
                continue;
            }
            Optional<Label> byName = keyMatch(vocabulary, facet, imported);
            if (byName.isPresent()) {
                labelIds.put(imported.id(), byName.get().id().value());
                warnings.add("label '" + displayName(imported) + "' mapped to the existing label "
                        + byName.get().id());
                continue;
            }
            Label candidate = Label.rehydrate(id, facet, imported.builtinKey(), imported.name(),
                    aliases(imported), status(imported.status()), null, actor.memberId(), now, now, 0);
            try {
                vocabulary.requireUniqueKeys(candidate);
            } catch (InvalidInputException conflict) {
                candidate = Label.rehydrate(id, facet, imported.builtinKey(), imported.name(), aliases(imported),
                        LabelStatus.RETIRED, null, actor.memberId(), now, now, 0);
                vocabulary.requireUniqueKeys(candidate);
                warnings.add("label '" + displayName(imported) + "' restored as retired: "
                        + conflict.code());
            }
            labels.save(candidate);
            vocabulary.put(candidate);
            labelIds.put(imported.id(), candidate.id().value());
            created++;
        }
        for (LabelImport redirect : redirects) {
            UUID target = resolveRedirect(redirect, importedLabels, labelIds);
            if (target == null) {
                warnings.add("merged label " + redirect.id() + " skipped: its target is unknown");
                continue;
            }
            labelIds.put(redirect.id(), target);
            if (vocabulary.find(new LabelId(redirect.id())).isPresent() || redirect.name() == null) {
                continue;
            }
            Label stub = Label.rehydrate(new LabelId(redirect.id()), Facet.parse(redirect.facet()),
                    redirect.builtinKey(), redirect.name(), List.of(), LabelStatus.MERGED, new LabelId(target),
                    actor.memberId(), now, now, 0);
            labels.save(stub);
            vocabulary.put(stub);
            created++;
        }
        return created;
    }

    private RestoredFolders restoreFolders(Actor actor, List<FolderImport> importedFolders,
            LabelVocabulary vocabulary, Map<UUID, UUID> labelIds, List<String> warnings, Instant now) {
        Map<UUID, UUID> folderMapping = new LinkedHashMap<>();
        CategoryTree tree = categories.load();
        int created = 0;
        for (FolderImport imported : importedFolders) {
            CategoryId id = new CategoryId(imported.id());
            CategoryId parent = imported.parentId() == null ? null
                    : Optional.ofNullable(folderMapping.get(imported.parentId())).map(CategoryId::new).orElse(null);
            if (imported.parentId() != null && parent == null) {
                warnings.add("folder '" + imported.name() + "' restored at the top level: its parent is unknown");
            }
            Optional<Category> local = tree.find(id);
            if (local.isEmpty()) {
                local = tree.childrenOf(parent).stream().filter(c -> c.nameKeyEquals(imported.name())).findFirst();
                if (local.isPresent()) {
                    warnings.add("folder '" + imported.name() + "' mapped to the existing folder "
                            + local.get().id());
                }
            }
            CategoryId target;
            if (local.isPresent()) {
                target = local.get().id();
            } else {
                try {
                    target = tree.create(id, parent, imported.name(), actor.memberId(), now).id();
                    created++;
                } catch (InvalidInputException rejected) {
                    warnings.add("folder '" + imported.name() + "' skipped: " + rejected.code());
                    continue;
                }
                Set<LabelId> implied = new LinkedHashSet<>();
                boolean typeTaken = false;
                for (UUID rawLabel : imported.impliedLabelIds()) {
                    UUID mapped = labelIds.get(rawLabel);
                    Optional<Label> label = mapped == null ? Optional.empty()
                            : vocabulary.find(new LabelId(mapped)).map(l -> vocabulary.resolve(l.id()));
                    if (label.isEmpty()) {
                        warnings.add("folder '" + imported.name() + "' lost an unknown implied label");
                        continue;
                    }
                    if (label.get().facet().singleValued()) {
                        if (typeTaken) {
                            continue;
                        }
                        typeTaken = true;
                    }
                    implied.add(label.get().id());
                }
                tree.changeImplications(target, implied, Subjects.fromViews(imported.impliedSubjects()),
                        vocabulary::facetOf, tree.require(target).version(), now);
            }
            folderMapping.put(imported.id(), target.value());
        }
        categories.save(tree, tree.effects(vocabulary::facetOf));
        return new RestoredFolders(folderMapping, created);
    }

    @Override
    public ClassificationOutcome importClassification(Actor actor, DocumentId documentId, UUID categoryId,
            List<AssignmentImport> assignments) {
        access.requireMember(actor);
        lock.shared();
        if (documents.findById(documentId).isEmpty()) {
            throw new NotFoundException("document", documentId.toString());
        }
        if (categoryId == null && (assignments == null || assignments.isEmpty())) {
            return ClassificationOutcome.NOTHING_TO_APPLY;
        }
        DocumentClassification classification = classifications.findById(documentId)
                .orElseGet(() -> DocumentClassification.unsorted(documentId));
        if (!classification.isPristine()) {
            return ClassificationOutcome.KEPT_EXISTING;
        }
        Instant now = clock.instant();
        LabelVocabulary vocabulary = labels.load();
        CategoryTree tree = categories.load();
        CategoryId folder = categoryId == null ? null
                : tree.find(new CategoryId(categoryId)).map(Category::id).orElse(null);
        Map<LabelId, LabelAssignment> restored = new LinkedHashMap<>();
        boolean typeTaken = false;
        for (AssignmentImport imported : assignments == null ? List.<AssignmentImport>of() : assignments) {
            Optional<Label> label = vocabulary.find(new LabelId(imported.labelId()))
                    .map(l -> vocabulary.resolve(l.id()));
            if (label.isEmpty()) {
                continue;
            }
            ReviewState state = reviewState(imported.reviewState());
            AssignmentOrigin origin = origin(imported.origin());
            if (origin == AssignmentOrigin.MANUAL && state != ReviewState.REJECTED) {
                state = ReviewState.CONFIRMED;
            }
            if (label.get().facet().singleValued() && state.effective()) {
                if (typeTaken) {
                    state = ReviewState.SUGGESTED;
                    origin = AssignmentOrigin.AUTOMATIC;
                } else {
                    typeTaken = true;
                }
            }
            LabelAssignment assignment = new LabelAssignment(label.get().id(), label.get().facet(), origin, state,
                    imported.confidence() == null ? null : new Confidence(imported.confidence()),
                    imported.sourceRef(), origin == AssignmentOrigin.MANUAL && imported.decidedBy() == null
                            ? actor.memberId() : imported.decidedBy(),
                    imported.decidedAt() == null ? now : imported.decidedAt());
            LabelAssignment previous = restored.get(label.get().id());
            if (previous == null || assignment.state().outranks(previous.state())) {
                restored.put(label.get().id(), assignment);
            }
        }
        if (folder == null && restored.isEmpty()) {
            return ClassificationOutcome.NOTHING_TO_APPLY;
        }
        classification.restore(folder, restored.values(), actor.memberId(), now);
        classifications.save(classification);
        return ClassificationOutcome.APPLIED;
    }

    private void adoptUnmodifiedBuiltin(Label local, LabelImport imported, LabelVocabulary vocabulary, Instant now) {
        if (!local.isBuiltin() || local.version() != 0 || local.name().isPresent() || imported.name() == null) {
            return;
        }
        local.rename(imported.name(), now);
        local.replaceAliases(aliases(imported), now);
        vocabulary.requireUniqueKeys(local);
        labels.save(local);
    }

    private static Optional<Label> keyMatch(LabelVocabulary vocabulary, Facet facet, LabelImport imported) {
        List<String> candidates = new ArrayList<>(aliases(imported));
        if (imported.name() != null) {
            candidates.add(0, imported.name());
        }
        for (String candidate : candidates) {
            Optional<Label> match = vocabulary.findByName(facet, candidate);
            if (match.isPresent()) {
                return match;
            }
        }
        return Optional.empty();
    }

    private static List<String> aliases(LabelImport imported) {
        return imported.aliases() == null ? List.of() : imported.aliases();
    }

    private static String displayName(LabelImport imported) {
        return imported.name() != null ? imported.name() : String.valueOf(imported.builtinKey());
    }

    private static UUID resolveRedirect(LabelImport redirect, List<LabelImport> all, Map<UUID, UUID> labelIds) {
        UUID current = redirect.mergedInto();
        for (int hops = 0; current != null && hops < 16; hops++) {
            UUID mapped = labelIds.get(current);
            if (mapped != null) {
                return mapped;
            }
            UUID step = current;
            current = all.stream().filter(candidate -> candidate.id().equals(step))
                    .map(LabelImport::mergedInto).findFirst().orElse(null);
        }
        return null;
    }

    private static LabelStatus status(String value) {
        try {
            LabelStatus status = LabelStatus.valueOf(value);
            return status == LabelStatus.MERGED ? LabelStatus.ACTIVE : status;
        } catch (IllegalArgumentException | NullPointerException unknown) {
            return LabelStatus.ACTIVE;
        }
    }

    private static ReviewState reviewState(String value) {
        try {
            return ReviewState.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException unknown) {
            return ReviewState.CONFIRMED;
        }
    }

    private static AssignmentOrigin origin(String value) {
        try {
            return AssignmentOrigin.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException unknown) {
            return AssignmentOrigin.MANUAL;
        }
    }
}
