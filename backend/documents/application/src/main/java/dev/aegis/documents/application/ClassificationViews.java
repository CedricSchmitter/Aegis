package dev.aegis.documents.application;

import dev.aegis.documents.application.api.CategoryRef;
import dev.aegis.documents.application.api.ClassificationView;
import dev.aegis.documents.application.api.LabelChip;
import dev.aegis.documents.application.api.PlacementView;
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
import dev.aegis.documents.domain.classification.LabelAssignment;
import dev.aegis.documents.domain.classification.ReviewState;
import dev.aegis.documents.domain.placement.PlacementSuggestion;
import dev.aegis.documents.domain.placement.PlacementSuggestionRepository;
import dev.aegis.documents.domain.placement.PlacementTarget;
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
 * Builds the classification read model: folder path, placement provenance, a pending placement suggestion with the
 * labels and subjects its target would add, own label chips (including pending suggestions) and chips inherited
 * from the folder. Inherited chips never duplicate an own effective label, and an own document type hides the
 * folder type.
 */
public class ClassificationViews {
    private final ClassificationRepository classifications;
    private final CategoryRepository categories;
    private final LabelRepository labels;
    private final PlacementSuggestionRepository placements;

    public ClassificationViews(ClassificationRepository classifications, CategoryRepository categories,
            LabelRepository labels, PlacementSuggestionRepository placements) {
        this.classifications = classifications;
        this.categories = categories;
        this.labels = labels;
        this.placements = placements;
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
        Map<DocumentId, PlacementSuggestion> suggestions = placements.findAll(ids);
        // Documents sharing a pending target: accepting one path can serve all of them.
        Map<String, Integer> shared = placements.countByKeys(suggestions.values().stream()
                .map(PlacementSuggestion::key).distinct().toList());
        for (DocumentId id : ids) {
            PlacementSuggestion suggestion = suggestions.get(id);
            views.put(id, build(stored.get(id), tree, vocabulary, suggestion,
                    suggestion == null ? 0 : Math.max(0, shared.getOrDefault(suggestion.key(), 1) - 1)));
        }
        return views;
    }

    static ClassificationView build(DocumentClassification classification, CategoryTree tree,
            LabelVocabulary vocabulary, PlacementSuggestion suggestion, int otherDocuments) {
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
        chips.sort(chipOrder());
        return new ClassificationView(categoryId == null ? null : categoryId.value(), path, chips,
                Subjects.toViews(implications.subjects().keySet()), origin(classification),
                suggestionView(suggestion, tree, vocabulary, shown, otherDocuments), classification.version());
    }

    private static Comparator<LabelChip> chipOrder() {
        return Comparator.comparing((LabelChip chip) -> CatalogViews.facet(chip.facet()).ordinal())
                .thenComparing(chip -> (chip.name() != null ? chip.name() : chip.builtinKey())
                        .toLowerCase(Locale.ROOT));
    }

    private static PlacementView.Origin origin(DocumentClassification classification) {
        DocumentClassification.Placement placement = classification.placement();
        if (placement.origin() == null && !placement.byMember()) {
            return PlacementView.Origin.NONE;
        }
        return new PlacementView.Origin(placement.origin() == null ? null : placement.origin().name(),
                placement.confidence() == null ? null : placement.confidence().value(), placement.sourceRef(),
                placement.by(), placement.at(), placement.byMember(), placement.automatic());
    }

    /**
     * The pending suggestion with the effect of its target: the labels and subjects the document would inherit
     * there and does not have yet. A target whose folder disappeared in the meantime is not shown.
     */
    private static PlacementView.Suggestion suggestionView(PlacementSuggestion suggestion, CategoryTree tree,
            LabelVocabulary vocabulary, Set<LabelId> ownLabels, int otherDocuments) {
        if (suggestion == null) {
            return null;
        }
        PlacementTarget target = suggestion.target();
        List<String> readable = target.readablePath(tree);
        if (readable.isEmpty()) {
            return null;
        }
        CategoryId effectSource = target.folderId().orElse(target.anchorId().orElse(null));
        CategoryTree.Implications implications = tree.implications(effectSource,
                id -> vocabulary.resolve(id).facet());
        List<LabelChip> impliedLabels = new ArrayList<>();
        implications.labels().forEach((labelId, folder) -> {
            Label label = vocabulary.resolve(labelId);
            if (!ownLabels.contains(label.id())) {
                impliedLabels.add(new LabelChip(label.id().value(), label.facet().name(),
                        label.builtinKey().orElse(null), label.name().orElse(null), LabelChip.SOURCE_FOLDER, null,
                        null, null, null, folder.value()));
            }
        });
        impliedLabels.sort(chipOrder());
        Long anchorVersion = target.anchorId().flatMap(tree::find).map(Category::version).orElse(null);
        return new PlacementView.Suggestion(suggestion.key(), target.folderId().map(CategoryId::value).orElse(null),
                target.anchorId().map(CategoryId::value).orElse(null), anchorVersion, readable, target.newPath(),
                suggestion.confidence(), suggestion.evidence(), suggestion.runRef(), suggestion.model(),
                suggestion.createdAt(), impliedLabels, Subjects.toViews(implications.subjects().keySet()),
                otherDocuments);
    }
}
