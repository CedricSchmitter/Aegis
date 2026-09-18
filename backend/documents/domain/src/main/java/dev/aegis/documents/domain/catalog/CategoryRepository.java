package dev.aegis.documents.domain.catalog;

import java.util.List;

/**
 * Persistence of the folder tree. Callers serialize structural changes (see the application catalog lock) so that
 * concurrent moves cannot form a cycle; folder versions are still checked optimistically.
 */
public interface CategoryRepository {
    CategoryTree load();

    /**
     * Stores the changed folders with optimistic version checks, deletes removed folders and replaces the derived
     * effect rows.
     */
    void save(CategoryTree tree, List<CategoryTree.CategoryEffects> effects);
}
