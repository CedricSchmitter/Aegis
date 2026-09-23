package dev.aegis.documents.application;

import dev.aegis.documents.application.ports.DocumentCatalog;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.ProcessingState;
import dev.aegis.documents.domain.Subject;
import dev.aegis.documents.domain.catalog.CategoryId;
import dev.aegis.documents.domain.catalog.CategoryTree;
import dev.aegis.documents.domain.catalog.Facet;
import dev.aegis.documents.domain.catalog.Label;
import dev.aegis.documents.domain.catalog.LabelId;
import dev.aegis.documents.domain.catalog.LabelVocabulary;
import dev.aegis.documents.domain.classification.DocumentClassification;
import dev.aegis.documents.domain.classification.LabelAssignment;
import dev.aegis.documents.domain.classification.ReviewState;
import dev.aegis.shared.DocumentId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory read model built from the same domain rules as the SQL adapter. It keeps the application tests fast;
 * the indexed PostgreSQL behavior and its identical results are verified by integration tests.
 */
public class InMemoryDocumentCatalog implements DocumentCatalog {
    private final InMemoryDocumentRepository documents;
    private final InMemoryClassificationRepository classifications;
    private final InMemoryCategoryRepository categories;
    private final InMemoryLabelRepository labels;

    public InMemoryDocumentCatalog(InMemoryDocumentRepository documents,
            InMemoryClassificationRepository classifications, InMemoryCategoryRepository categories,
            InMemoryLabelRepository labels) {
        this.documents = documents;
        this.classifications = classifications;
        this.categories = categories;
        this.labels = labels;
    }

    @Override
    public Page search(Criteria criteria, Sort sort, int offset, int limit) {
        List<Document> matching = matching(criteria);
        Comparator<Document> order = switch (sort) {
            case NEWEST -> Comparator.comparing(Document::uploadedAt).reversed();
            case OLDEST -> Comparator.comparing(Document::uploadedAt);
            case TITLE -> Comparator.comparing(d -> d.title().toLowerCase(Locale.ROOT));
        };
        List<DocumentId> ids = matching.stream().sorted(order).skip(offset).limit(limit).map(Document::id).toList();
        return new Page(ids, matching.size());
    }

    @Override
    public GroupCounts countGroup(Criteria criteria, Group group) {
        Map<String, Long> counts = new LinkedHashMap<>();
        long missing = 0;
        for (Document document : matching(criteria)) {
            Set<String> values = effectiveValues(document).getOrDefault(group, Set.of());
            if (values.isEmpty()) {
                missing++;
            }
            values.forEach(value -> counts.merge(value, 1L, Long::sum));
        }
        return new GroupCounts(counts, missing);
    }

    @Override
    public long countNotArchived(Criteria criteria) {
        return matching(criteria).stream().filter(d -> d.state() != ProcessingState.ARCHIVED).count();
    }

    @Override
    public long countAll() {
        return ordinary().size();
    }

    @Override
    public Map<UUID, Long> documentsPerCategory() {
        Map<UUID, Long> counts = new LinkedHashMap<>();
        for (Document document : ordinary()) {
            classifications.findById(document.id()).flatMap(DocumentClassification::categoryId)
                    .ifPresent(category -> counts.merge(category.value(), 1L, Long::sum));
        }
        return counts;
    }

    @Override
    public long countUnsorted() {
        return ordinary().stream().filter(document -> classifications.findById(document.id())
                .flatMap(DocumentClassification::categoryId).isEmpty()).count();
    }

    @Override
    public Map<UUID, LabelUsage> labelUsage() {
        Map<UUID, long[]> counts = new LinkedHashMap<>();
        for (Document document : ordinary()) {
            DocumentClassification classification = classifications.findById(document.id()).orElse(null);
            if (classification == null) {
                continue;
            }
            for (LabelAssignment assignment : classification.assignments()) {
                long[] usage = counts.computeIfAbsent(assignment.labelId().value(), key -> new long[2]);
                if (assignment.effective()) {
                    usage[0]++;
                } else if (assignment.state() == ReviewState.SUGGESTED) {
                    usage[1]++;
                }
            }
        }
        Map<UUID, LabelUsage> usage = new LinkedHashMap<>();
        counts.forEach((id, values) -> usage.put(id, new LabelUsage(values[0], values[1])));
        return usage;
    }

    @Override
    public Set<UUID> trashedDocumentIds() {
        return documents.findTrashed().stream().map(document -> document.id().value())
                .collect(java.util.stream.Collectors.toSet());
    }

    /** Like the database catalog, every count and search covers documents outside the trash only. */
    private List<Document> ordinary() {
        return documents.findAll().stream().filter(document -> !document.isTrashed()).toList();
    }

    private List<Document> matching(Criteria criteria) {
        List<Document> matching = new ArrayList<>();
        for (Document document : ordinary()) {
            if (matches(document, criteria)) {
                matching.add(document);
            }
        }
        return matching;
    }

    private boolean matches(Document document, Criteria criteria) {
        Optional<CategoryId> category = classifications.findById(document.id())
                .flatMap(DocumentClassification::categoryId);
        if (criteria.unsortedOnly() && category.isPresent()) {
            return false;
        }
        if (criteria.categoryScope() != null
                && !category.map(id -> criteria.categoryScope().contains(id.value())).orElse(false)) {
            return false;
        }
        if (criteria.text() != null) {
            boolean titleHit = document.title().toLowerCase(Locale.ROOT)
                    .contains(criteria.text().titleFragment().toLowerCase(Locale.ROOT));
            boolean contentHit = document.paperlessDocumentId()
                    .map(id -> criteria.text().paperlessIds().contains(id)).orElse(false);
            if (!titleHit && !contentHit) {
                return false;
            }
        }
        Map<Group, Set<String>> effective = effectiveValues(document);
        for (Map.Entry<Group, ValueFilter> filter : criteria.filters().entrySet()) {
            Set<String> values = effective.getOrDefault(filter.getKey(), Set.of());
            boolean hit = filter.getValue().matchAll() ? values.containsAll(filter.getValue().values())
                    : filter.getValue().values().stream().anyMatch(values::contains);
            if (!hit) {
                return false;
            }
        }
        for (Group group : criteria.missing()) {
            if (!effective.getOrDefault(group, Set.of()).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private Map<Group, Set<String>> effectiveValues(Document document) {
        CategoryTree tree = categories.load();
        LabelVocabulary vocabulary = labels.load();
        DocumentClassification classification = classifications.findById(document.id()).orElse(null);
        Map<Group, Set<String>> values = new EnumMap<>(Group.class);
        Set<Subject> subjects = new LinkedHashSet<>(document.subjects());
        boolean ownType = false;
        if (classification != null) {
            for (LabelAssignment assignment : classification.assignments()) {
                if (!assignment.effective() || vocabulary.find(assignment.labelId()).isEmpty()) {
                    continue;
                }
                Label label = vocabulary.resolve(assignment.labelId());
                ownType |= label.facet().singleValued();
                add(values, label);
            }
            CategoryTree.Implications implications = tree.implications(
                    classification.categoryId().orElse(null), id -> vocabulary.resolve(id).facet());
            for (LabelId implied : implications.labels().keySet()) {
                Label label = vocabulary.resolve(implied);
                if (label.facet().singleValued() && ownType) {
                    continue;
                }
                add(values, label);
            }
            subjects.addAll(implications.subjects().keySet());
        }
        values.put(Group.SUBJECT, subjects.stream().map(Subjects::key)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
        return values;
    }

    private static void add(Map<Group, Set<String>> values, Label label) {
        values.computeIfAbsent(group(label.facet()), key -> new LinkedHashSet<>()).add(label.id().toString());
    }

    private static Group group(Facet facet) {
        return switch (facet) {
            case DOCUMENT_TYPE -> Group.DOCUMENT_TYPE;
            case ORGANIZATION -> Group.ORGANIZATION;
            case TOPIC -> Group.TOPIC;
            case TAG -> Group.TAG;
        };
    }
}
