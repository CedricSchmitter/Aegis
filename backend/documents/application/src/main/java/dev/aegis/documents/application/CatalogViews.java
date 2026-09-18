package dev.aegis.documents.application;

import dev.aegis.documents.application.api.CategoryView;
import dev.aegis.documents.application.api.LabelView;
import dev.aegis.documents.application.ports.DocumentCatalog.LabelUsage;
import dev.aegis.documents.domain.catalog.Category;
import dev.aegis.documents.domain.catalog.CategoryTree;
import dev.aegis.documents.domain.catalog.Facet;
import dev.aegis.documents.domain.catalog.Label;
import dev.aegis.documents.domain.catalog.LabelId;
import dev.aegis.shared.StaleVersionException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Read-model mapping for folders and labels shared by the catalog services. */
final class CatalogViews {
    private static final LabelUsage UNUSED = new LabelUsage(0, 0);

    private CatalogViews() {
    }

    static CategoryView category(Category category, Map<UUID, Long> documentCounts) {
        return new CategoryView(category.id().value(), category.parentId().map(p -> p.value()).orElse(null),
                category.name(), category.impliedLabels().stream().map(LabelId::value).toList(),
                Subjects.toViews(category.impliedSubjects()),
                documentCounts.getOrDefault(category.id().value(), 0L), category.version());
    }

    static LabelView label(Label label, Map<UUID, LabelUsage> usage, Map<LabelId, Long> folderCounts) {
        LabelUsage used = usage.getOrDefault(label.id().value(), UNUSED);
        return new LabelView(label.id().value(), label.facet().name(), label.builtinKey().orElse(null),
                label.name().orElse(null), List.copyOf(label.aliases()), label.status().name(),
                label.mergedInto().map(LabelId::value).orElse(null), used.effective(), used.suggested(),
                folderCounts.getOrDefault(label.id(), 0L), label.version());
    }

    /** Number of folders whose own mapping names each label. */
    static Map<LabelId, Long> folderCounts(CategoryTree tree) {
        Map<LabelId, Long> counts = new HashMap<>();
        tree.all().forEach(category -> category.impliedLabels().forEach(label -> counts.merge(label, 1L,
                Long::sum)));
        return counts;
    }

    /** Readable name for exports: the member-authored name, else the built-in key in words. */
    static String displayName(Label label) {
        if (label.name().isPresent()) {
            return label.name().get();
        }
        String key = label.builtinKey().orElse(label.id().toString());
        String words = key.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    static Facet facet(String value) {
        return Facet.parse(value);
    }

    static void requireVersion(String resource, Object id, long actual, long expected) {
        if (actual != expected) {
            throw new StaleVersionException(resource, id.toString(), expected, actual);
        }
    }
}
