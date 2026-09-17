package dev.aegis.documents.application;

import dev.aegis.documents.application.api.CategoryRef;
import dev.aegis.documents.application.api.ClassificationView;
import dev.aegis.documents.application.api.LabelChip;
import dev.aegis.documents.domain.catalog.CategoryId;
import dev.aegis.documents.domain.catalog.CategoryRepository;
import dev.aegis.documents.domain.catalog.CategoryTree;
import dev.aegis.documents.domain.catalog.Label;
import dev.aegis.documents.domain.catalog.LabelId;
import dev.aegis.documents.domain.catalog.LabelRepository;
import dev.aegis.documents.domain.catalog.LabelVocabulary;
import dev.aegis.documents.domain.classification.ClassificationRepository;
import dev.aegis.documents.domain.classification.DocumentClassification;
import dev.aegis.documents.domain.classification.LabelAssignment;
import dev.aegis.documents.domain.classification.ReviewState;
import dev.aegis.shared.DocumentId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds the classification read model: folder path, own label chips (including pending suggestions) and chips
 * inherited from the folder. Inherited chips never duplicate an own effective label, and an own document type hides
 * the folder type.
 */
public class ClassificationViews {
    private final ClassificationRepository classifications;
    private final CategoryRepository categories;
    private final LabelRepository labels;

    public ClassificationViews(ClassificationRepository classifications, CategoryRepository categories,
            LabelRepository labels) {
        this.classifications = classifications;
        this.categories = categories;
        this.labels = labels;
    }

    public ClassificationView viewOf(DocumentId id) {
        return viewsOf(List.of(id), categories.load(), labels.load()).getOrDefault(id, ClassificationView.UNSORTED);
    }

    public Map<DocumentId, ClassificationView> viewsOf(Collection<DocumentId> ids, CategoryTree tree,
            LabelVocabulary vocabulary) {
        Map<DocumentId, ClassificationView> views = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return views;
        }
        Map<DocumentId, DocumentClassification> stored = classifications.findAll(ids);
        for (DocumentId id : ids) {
            views.put(id, build(stored.get(id), tree, vocabulary));
        }
        return views;
    }

    static ClassificationView build(DocumentClassification classification, CategoryTree tree,
            LabelVocabulary vocabulary) {
        if (classification == null) {
            return ClassificationView.UNSORTED;
        }
        CategoryId categoryId = classification.categoryId().filter(id -> tree.find(id).isPresent()).orElse(null);
        List<CategoryRef> path = categoryId == null ? List.of() : tree.path(categoryId).stream()
                .map(c -> new CategoryRef(c.id().value(), c.name())).toList();
        CategoryTree.Implications implications = tree.implications(categoryId,
                id -> vocabulary.resolve(id).facet());
        Set<LabelId> inherited = new HashSet<>();
        implications.labels().keySet().forEach(id -> inherited.add(vocabulary.resolve(id).id()));

        List<LabelChip> chips = new ArrayList<>();
        Set<LabelId> shown = new HashSet<>();
        boolean ownType = false;
        for (LabelAssignment assignment : classification.assignments()) {
            if (assignment.state() == ReviewState.REJECTED || vocabulary.find(assignment.labelId()).isEmpty()) {
                continue;
            }
            Label label = vocabulary.resolve(assignment.labelId());
            if (!assignment.effective() && inherited.contains(label.id()) || !shown.add(label.id())) {
                continue;
            }
            ownType |= assignment.effective() && label.facet().singleValued();
            chips.add(new LabelChip(label.id().value(), label.facet().name(), label.builtinKey().orElse(null),
                    label.name().orElse(null), LabelChip.SOURCE_OWN, assignment.origin().name(),
                    assignment.state().name(),
                    assignment.confidence() == null ? null : assignment.confidence().value(),
                    assignment.sourceRef(), null));
        }
        for (Map.Entry<LabelId, CategoryId> entry : implications.labels().entrySet()) {
            Label label = vocabulary.resolve(entry.getKey());
            if (shown.contains(label.id()) || label.facet().singleValued() && ownType) {
                continue;
            }
            shown.add(label.id());
            chips.add(new LabelChip(label.id().value(), label.facet().name(), label.builtinKey().orElse(null),
                    label.name().orElse(null), LabelChip.SOURCE_FOLDER, null, null, null, null,
                    entry.getValue().value()));
        }
        chips.sort(Comparator.comparing((LabelChip chip) -> CatalogViews.facet(chip.facet()).ordinal())
                .thenComparing(chip -> (chip.name() != null ? chip.name() : chip.builtinKey())
                        .toLowerCase(Locale.ROOT)));
        return new ClassificationView(categoryId == null ? null : categoryId.value(), path, chips,
                Subjects.toViews(implications.subjects().keySet()), classification.version());
    }
}
