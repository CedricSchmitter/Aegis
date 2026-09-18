package dev.aegis.documents.application;

import dev.aegis.documents.application.api.CatalogQueries;
import dev.aegis.documents.application.api.CategoryListing;
import dev.aegis.documents.application.api.CategoryView;
import dev.aegis.documents.application.api.LabelView;
import dev.aegis.documents.application.ports.DocumentCatalog;
import dev.aegis.documents.domain.catalog.Category;
import dev.aegis.documents.domain.catalog.CategoryId;
import dev.aegis.documents.domain.catalog.CategoryRepository;
import dev.aegis.documents.domain.catalog.CategoryTree;
import dev.aegis.documents.domain.catalog.Facet;
import dev.aegis.documents.domain.catalog.LabelId;
import dev.aegis.documents.domain.catalog.LabelRepository;
import dev.aegis.documents.domain.catalog.LabelVocabulary;
import dev.aegis.shared.Actor;
import dev.aegis.shared.HouseholdAccess;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Read access to the folder tree and the vocabulary, including usage counts. Both members see the same catalog. */
public class CatalogQueryService implements CatalogQueries {
    private final CategoryRepository categories;
    private final LabelRepository labels;
    private final DocumentCatalog catalog;
    private final HouseholdAccess access;

    public CatalogQueryService(CategoryRepository categories, LabelRepository labels, DocumentCatalog catalog,
            HouseholdAccess access) {
        this.categories = categories;
        this.labels = labels;
        this.catalog = catalog;
        this.access = access;
    }

    @Override
    public CategoryListing categories(Actor actor) {
        access.requireMember(actor);
        CategoryTree tree = categories.load();
        Map<UUID, Long> counts = catalog.documentsPerCategory();
        List<CategoryView> views = new ArrayList<>();
        appendDepthFirst(tree, null, counts, views);
        return new CategoryListing(views, catalog.countUnsorted(), catalog.countAll());
    }

    @Override
    public List<LabelView> labels(Actor actor) {
        access.requireMember(actor);
        LabelVocabulary vocabulary = labels.load();
        Map<UUID, DocumentCatalog.LabelUsage> usage = catalog.labelUsage();
        Map<LabelId, Long> folders = CatalogViews.folderCounts(categories.load());
        return vocabulary.all().stream().map(label -> CatalogViews.label(label, usage, folders))
                .sorted(Comparator.comparing((LabelView view) -> Facet.parse(view.facet()).ordinal())
                        .thenComparing(view -> sortKey(view)))
                .toList();
    }

    @Override
    public Optional<LabelView> resolveLabel(Actor actor, String facet, String name) {
        access.requireMember(actor);
        LabelVocabulary vocabulary = labels.load();
        return vocabulary.findByName(Facet.parse(facet), name)
                .map(label -> CatalogViews.label(label, catalog.labelUsage(),
                        CatalogViews.folderCounts(categories.load())));
    }

    private static String sortKey(LabelView view) {
        String name = view.name() != null ? view.name() : view.builtinKey();
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    private static void appendDepthFirst(CategoryTree tree, CategoryId parent, Map<UUID, Long> counts,
            List<CategoryView> target) {
        for (Category child : tree.childrenOf(parent)) {
            target.add(CatalogViews.category(child, counts));
            appendDepthFirst(tree, child.id(), counts, target);
        }
    }
}
