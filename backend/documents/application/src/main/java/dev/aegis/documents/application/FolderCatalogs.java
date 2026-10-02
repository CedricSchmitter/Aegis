package dev.aegis.documents.application;

import dev.aegis.documents.application.api.AutomaticClassification.FolderCatalog;
import dev.aegis.documents.application.api.AutomaticClassification.FolderEntry;
import dev.aegis.documents.application.api.SubjectView;
import dev.aegis.documents.domain.catalog.CatalogNames;
import dev.aegis.documents.domain.catalog.Category;
import dev.aegis.documents.domain.catalog.CategoryId;
import dev.aegis.documents.domain.catalog.CategoryTree;
import dev.aegis.documents.domain.catalog.Label;
import dev.aegis.documents.domain.catalog.LabelId;
import dev.aegis.documents.domain.catalog.LabelVocabulary;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Builds the folder tree as classifier context (ADR-0005): full paths with the labels and subjects each
 * folder passes on and how many documents are filed directly in it.
 *
 * <p>The order is deterministic - paths compared segment by segment through their normalized match keys, ties
 * broken by folder id - so the same tree always produces the same prompt prefix and can be cached by a provider
 * that supports it.
 *
 * <p>Reduction strategy when the tree grows past {@code maxFolders}: folders that hold no documents anywhere in
 * their subtree are dropped first, deepest and alphabetically last first; if that is still not enough, the
 * deepest remaining folders are dropped. The number of omitted folders is reported so the prompt can say the
 * catalog is incomplete, and a proposal may still anchor at any folder that is listed.
 */
final class FolderCatalogs {
    private FolderCatalogs() {
    }

    static FolderCatalog build(CategoryTree tree, LabelVocabulary vocabulary, Map<UUID, Long> documentCounts,
            int maxFolders) {
        if (tree.isEmpty()) {
            return FolderCatalog.EMPTY;
        }
        List<Category> ordered = new ArrayList<>(tree.all());
        Map<CategoryId, List<String>> keys = new LinkedHashMap<>();
        Map<CategoryId, List<String>> names = new LinkedHashMap<>();
        for (Category category : ordered) {
            List<String> path = tree.pathNames(category.id());
            names.put(category.id(), path);
            keys.put(category.id(), path.stream().map(CatalogNames::matchKey).toList());
        }
        ordered.sort(Comparator.comparing((Category category) -> keys.get(category.id()),
                FolderCatalogs::comparePaths).thenComparing(category -> category.id().value()));

        Set<CategoryId> kept = retain(tree, ordered, documentCounts, maxFolders);
        List<FolderEntry> entries = new ArrayList<>();
        for (Category category : ordered) {
            if (!kept.contains(category.id())) {
                continue;
            }
            CategoryTree.Implications implications = tree.implications(category.id(),
                    id -> vocabulary.resolve(id).facet());
            List<String> impliedLabels = implications.labels().keySet().stream()
                    .map(id -> token(vocabulary.resolve(id))).sorted().toList();
            List<String> impliedSubjects = Subjects.toViews(implications.subjects().keySet()).stream()
                    .map(FolderCatalogs::token).sorted().toList();
            entries.add(new FolderEntry(category.id().value(), names.get(category.id()), impliedLabels,
                    impliedSubjects, documentCounts.getOrDefault(category.id().value(), 0L)));
        }
        return new FolderCatalog(entries, ordered.size() - entries.size());
    }

    /** The labels shown in the vocabulary block use the same token, so the model can match them literally. */
    private static String token(Label label) {
        return label.name().orElseGet(() -> "builtin:" + label.builtinKey().orElse(label.id().toString()));
    }

    private static String token(SubjectView subject) {
        return subject.memberId() == null ? subject.kind() : subject.kind() + ":" + subject.memberId();
    }

    private static int comparePaths(List<String> left, List<String> right) {
        for (int i = 0; i < Math.min(left.size(), right.size()); i++) {
            int order = left.get(i).compareTo(right.get(i));
            if (order != 0) {
                return order;
            }
        }
        return Integer.compare(left.size(), right.size());
    }

    private static Set<CategoryId> retain(CategoryTree tree, List<Category> ordered, Map<UUID, Long> counts,
            int maxFolders) {
        Set<CategoryId> kept = new HashSet<>();
        ordered.forEach(category -> kept.add(category.id()));
        if (kept.size() <= maxFolders) {
            return kept;
        }
        // Drop empty folders first, then the deepest ones; both passes walk the deterministic order backwards.
        List<Category> candidates = new ArrayList<>(ordered);
        candidates.sort(Comparator.comparingInt((Category category) -> tree.depth(category.id())).reversed());
        for (Category category : candidates) {
            if (kept.size() <= maxFolders) {
                break;
            }
            if (used(tree, category.id(), counts) == 0) {
                kept.remove(category.id());
            }
        }
        for (Category category : candidates) {
            if (kept.size() <= maxFolders) {
                break;
            }
            kept.remove(category.id());
        }
        // A listed folder must keep a listed parent, otherwise its path would be unreachable for an anchor.
        for (Category category : ordered) {
            for (CategoryId ancestor : tree.path(category.id()).stream().map(Category::id).toList()) {
                if (!kept.contains(ancestor)) {
                    kept.remove(category.id());
                }
            }
        }
        return kept;
    }

    private static long used(CategoryTree tree, CategoryId id, Map<UUID, Long> counts) {
        return tree.subtree(id).stream().mapToLong(folder -> counts.getOrDefault(folder.value(), 0L)).sum();
    }

    static List<LabelId> impliedLabelIds(CategoryTree tree, CategoryId folder, LabelVocabulary vocabulary) {
        return List.copyOf(tree.implications(folder, id -> vocabulary.resolve(id).facet()).labels().keySet());
    }
}
