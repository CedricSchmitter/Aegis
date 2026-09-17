package dev.aegis.documents.application;

import dev.aegis.documents.domain.catalog.Category;
import dev.aegis.documents.domain.catalog.CategoryId;
import dev.aegis.documents.domain.catalog.CategoryRepository;
import dev.aegis.documents.domain.catalog.CategoryTree;
import dev.aegis.shared.StaleVersionException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Test double; PostgreSQL semantics, locking and effect rows are covered by the integration tests. */
public class InMemoryCategoryRepository implements CategoryRepository {
    private final Map<CategoryId, Category> store = new LinkedHashMap<>();
    final List<CategoryTree.CategoryEffects> effects = new ArrayList<>();

    @Override
    public CategoryTree load() {
        return new CategoryTree(store.values().stream().map(InMemoryCategoryRepository::copy).toList());
    }

    @Override
    public void save(CategoryTree tree, List<CategoryTree.CategoryEffects> rebuilt) {
        CategoryTree.Changes changes = tree.changes();
        for (Category category : changes.upserts()) {
            Category stored = store.get(category.id());
            if (stored != null && stored.version() != category.version()) {
                throw new StaleVersionException("category", category.id().toString(), category.version(),
                        stored.version());
            }
            store.put(category.id(), bump(category));
        }
        changes.deletions().forEach(store::remove);
        effects.clear();
        effects.addAll(rebuilt);
    }

    private static Category copy(Category category) {
        return rehydrate(category, category.version());
    }

    private static Category bump(Category category) {
        return rehydrate(category, category.version() + 1);
    }

    private static Category rehydrate(Category category, long version) {
        return Category.rehydrate(category.id(), category.parentId().orElse(null), category.name(),
                Set.copyOf(category.impliedLabels()), Set.copyOf(category.impliedSubjects()),
                category.createdBy().orElse(null), category.createdAt(), category.updatedAt(), version);
    }
}
