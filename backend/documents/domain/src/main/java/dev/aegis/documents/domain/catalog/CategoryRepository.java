package dev.aegis.documents.domain.catalog;

import java.util.List;

/**
 * Persistence of the folder tree. {@link #loadForUpdate()} must serialize structural changes (for example with a
 * database lock held until the surrounding transaction ends) so that concurrent moves cannot form a cycle.
 */
public interface CategoryRepository {
    /** Current tree without locking, for reads. */
    CategoryTree load();

    /** Current tree with exclusive structural access for the rest of the transaction. */
    CategoryTree loadForUpdate();

    /**
     * Stores the changed folders with optimistic version checks, deletes removed folders and replaces the derived
     * effect rows.
     */
    void save(CategoryTree tree, List<CategoryTree.CategoryEffects> effects);
}
