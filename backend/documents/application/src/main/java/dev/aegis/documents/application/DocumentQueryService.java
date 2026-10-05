package dev.aegis.documents.application;

import dev.aegis.documents.application.api.ArchiveHealth;
import dev.aegis.documents.application.api.ArchivePdf;
import dev.aegis.documents.application.api.CatalogExport;
import dev.aegis.documents.application.api.ClassificationView;
import dev.aegis.documents.application.api.ContentSearchState;
import dev.aegis.documents.application.api.CountAccuracy;
import dev.aegis.documents.application.api.DocumentExportQuery;
import dev.aegis.documents.application.api.DocumentExportRecord;
import dev.aegis.documents.application.api.DocumentQueries;
import dev.aegis.documents.application.api.DocumentReference;
import dev.aegis.documents.application.api.DocumentReferenceQuery;
import dev.aegis.documents.application.api.DocumentSearchRequest;
import dev.aegis.documents.application.api.DocumentSearchResult;
import dev.aegis.documents.application.api.DocumentStatusQuery;
import dev.aegis.documents.application.api.DocumentSummary;
import dev.aegis.documents.application.api.DocumentThumbnail;
import dev.aegis.documents.application.api.FacetCounts;
import dev.aegis.documents.application.api.FilenameView;
import dev.aegis.documents.application.api.OriginalContent;
import dev.aegis.documents.application.api.OriginalUnavailableException;
import dev.aegis.documents.application.api.SearchLimitation;
import dev.aegis.documents.application.ports.ArchiveUnavailableException;
import dev.aegis.documents.application.ports.DocumentCatalog.Criteria;
import dev.aegis.documents.application.ports.DocumentCatalog.Group;
import dev.aegis.documents.application.ports.DocumentCatalog.ValueFilter;
import dev.aegis.documents.application.ports.DocumentCatalog;
import dev.aegis.documents.application.ports.OriginalStore;
import dev.aegis.documents.application.ports.PaperlessArchive;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.DocumentRepository;
import dev.aegis.documents.domain.ProcessingState;
import dev.aegis.documents.domain.analysis.DocumentAnalysis;
import dev.aegis.documents.domain.analysis.DocumentAnalysisRepository;
import dev.aegis.documents.domain.catalog.Category;
import dev.aegis.documents.domain.catalog.CategoryId;
import dev.aegis.documents.domain.catalog.CategoryRepository;
import dev.aegis.documents.domain.catalog.CategoryTree;
import dev.aegis.documents.domain.catalog.Facet;
import dev.aegis.documents.domain.catalog.Label;
import dev.aegis.documents.domain.catalog.LabelId;
import dev.aegis.documents.domain.catalog.LabelRepository;
import dev.aegis.documents.domain.catalog.LabelVocabulary;
import dev.aegis.documents.domain.classification.ClassificationRepository;
import dev.aegis.documents.domain.classification.DocumentClassification;
import dev.aegis.documents.domain.classification.LabelAssignment;
import dev.aegis.documents.domain.naming.FriendlyFilename;
import dev.aegis.documents.domain.placement.PlacementSuggestion;
import dev.aegis.documents.domain.placement.PlacementSuggestionRepository;
import dev.aegis.documents.domain.placement.PlacementTarget;
import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.HouseholdAccess;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.NotFoundException;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Read side of the shared collection: structured search with facet counts, document details, originals, exports and
 * archive health. Not wrapped in a database transaction because archive calls happen inline.
 */
public class DocumentQueryService implements DocumentQueries, DocumentReferenceQuery, DocumentExportQuery,
        DocumentStatusQuery {
    private final DocumentRepository documents;
    private final PaperlessArchive archive;
    private final OriginalStore store;
    private final DocumentPresenter presenter;
    private final DocumentCatalog catalog;
    private final CategoryRepository categories;
    private final LabelRepository labels;
    private final ClassificationRepository classifications;
    private final ClassificationViews classificationViews;
    private final PlacementSuggestionRepository placements;
    private final DocumentAnalysisRepository analyses;
    private final AnalysisSettings analysisSettings;
    private final HouseholdAccess access;
    private final Clock clock;

    public DocumentQueryService(DocumentRepository documents, PaperlessArchive archive, OriginalStore store,
            DocumentPresenter presenter, DocumentCatalog catalog, CategoryRepository categories,
            LabelRepository labels, ClassificationRepository classifications, ClassificationViews classificationViews,
            PlacementSuggestionRepository placements, DocumentAnalysisRepository analyses,
            AnalysisSettings analysisSettings, HouseholdAccess access, Clock clock) {
        this.documents = documents;
        this.archive = archive;
        this.store = store;
        this.presenter = presenter;
        this.catalog = catalog;
        this.categories = categories;
        this.labels = labels;
        this.classifications = classifications;
        this.classificationViews = classificationViews;
        this.placements = placements;
        this.analyses = analyses;
        this.analysisSettings = analysisSettings;
        this.access = access;
        this.clock = clock;
    }

    @Override
    public DocumentSummary get(Actor actor, DocumentId id) {
        access.requireMember(actor);
        return presenter.summarize(load(id));
    }

    @Override
    public List<DocumentSummary> trashed(Actor actor) {
        access.requireMember(actor);
        return documents.findTrashed().stream().map(presenter::summarize).toList();
    }

    @Override
    public DocumentSearchResult search(Actor actor, DocumentSearchRequest request) {
        // Open to household processes: the review reconciler reads the collection through it (ADR-0009).
        access.requireReader(actor);
        CategoryTree tree = categories.load();
        LabelVocabulary vocabulary = labels.load();
        Criteria filters = criteria(request, tree, vocabulary);
        String text = request.text() == null ? "" : request.text().trim();
        ContentSearchState contentSearch = ContentSearchState.NOT_APPLIED;
        Criteria criteria = filters;
        SearchLimitation limitation = null;
        Long archiveTotal = null;
        if (!text.isEmpty()) {
            List<Integer> hits = List.of();
            try {
                var retrieved = archive.searchContent(text);
                hits = retrieved.ids();
                contentSearch = retrieved.state();
                limitation = retrieved.limitation();
                archiveTotal = retrieved.archiveTotal();
            } catch (ArchiveUnavailableException unavailable) {
                contentSearch = ContentSearchState.UNAVAILABLE;
                limitation = SearchLimitation.ARCHIVE_FAILURE;
            }
            criteria = new Criteria(new DocumentCatalog.TextMatch(text, hits), filters.categoryScope(),
                    filters.unsortedOnly(), filters.filters(), filters.missing());
        }
        DocumentCatalog.Page page = catalog.search(criteria, sort(request.sort()),
                request.page() * request.size(), request.size());
        Map<DocumentId, ClassificationView> views = classificationViews.viewsOf(page.ids(), tree, vocabulary);
        List<DocumentSummary> items = presenter.summarizeAll(documents.findByIds(page.ids()), views);
        long notContentIndexed = text.isEmpty() ? 0 : catalog.countNotArchived(criteria.withoutText());
        boolean unindexedFacetCandidates = !text.isEmpty() && Arrays.stream(Group.values())
                .anyMatch(group -> catalog.countNotArchived(filters.withoutGroup(group)) > 0);
        return new DocumentSearchResult(items, contentSearch, catalog.countAll(), page.total(), request.page(),
                request.size(), facetCounts(criteria), notContentIndexed, limitation, archiveTotal,
                contentSearch == ContentSearchState.LIMITED || contentSearch == ContentSearchState.UNAVAILABLE
                        || unindexedFacetCandidates
                        ? CountAccuracy.LOWER_BOUND
                        : CountAccuracy.EXACT);
    }

    /** The unchanged original bytes, named with the friendly filename. */
    @Override
    public OriginalContent original(Actor actor, DocumentId id) {
        access.requireMember(actor);
        Document document = load(id);
        return originalOf(document, friendlyFilename(document));
    }

    @Override
    public ArchivePdf archivePdf(Actor actor, DocumentId id) {
        access.requireMember(actor);
        Document document = load(id);
        if (document.state() != ProcessingState.ARCHIVED) {
            throw new NotFoundException("archivePdf", id.toString());
        }
        byte[] bytes = archive.fetchArchivePdf(document.paperlessDocumentId().orElseThrow())
                .orElseThrow(() -> new NotFoundException("archivePdf", id.toString()));
        return new ArchivePdf(FriendlyFilename.withExtensionFor(friendlyFilename(document), "application/pdf"),
                bytes);
    }

    @Override
    public DocumentThumbnail thumbnail(Actor actor, DocumentId id) {
        access.requireMember(actor);
        Document document = load(id);
        if (document.state() != ProcessingState.ARCHIVED) {
            throw new NotFoundException("thumbnail", id.toString());
        }
        return archive.fetchThumbnail(document.paperlessDocumentId().orElseThrow());
    }

    @Override
    public Optional<DocumentReference> findReference(DocumentId id) {
        return documents.findById(id).map(d -> new DocumentReference(d.id(), d.title(), d.isTrashed()));
    }

    @Override
    public List<DocumentExportRecord> exportAll(Actor actor) {
        access.requireMember(actor);
        List<Document> all = documents.findAll();
        CategoryTree tree = categories.load();
        LabelVocabulary vocabulary = labels.load();
        List<DocumentId> ids = all.stream().map(Document::id).toList();
        Map<DocumentId, DocumentClassification> stored = classifications.findAll(ids);
        Map<DocumentId, ClassificationView> views = classificationViews.viewsOf(ids, tree, vocabulary);
        Map<DocumentId, DocumentAnalysis> storedAnalyses = analyses.findAll(ids);
        Map<DocumentId, PlacementSuggestion> suggestions = placements.findAll(ids);
        List<DocumentExportRecord> records = new ArrayList<>();
        for (Document document : all) {
            DocumentAnalysis analysis = storedAnalyses.get(document.id());
            FilenameView filename = AnalysisViews.filename(document,
                    views.getOrDefault(document.id(), ClassificationView.UNSORTED), analysis,
                    analysisSettings.filenameLanguage());
            records.add(presenter.exportRecord(document, exportClassification(stored.get(document.id()), tree,
                    vocabulary, suggestions.get(document.id())), filename, analysis));
        }
        return records;
    }

    @Override
    public OriginalContent originalForExport(Actor actor, DocumentId id) {
        return original(actor, id);
    }

    @Override
    public CatalogExport exportCatalog(Actor actor) {
        access.requireMember(actor);
        CategoryTree tree = categories.load();
        LabelVocabulary vocabulary = labels.load();
        List<CatalogExport.LabelEntry> labelEntries = vocabulary.all().stream()
                .sorted(Comparator.comparing((Label label) -> label.facet().ordinal())
                        .thenComparing(CatalogViews::displayName))
                .map(label -> new CatalogExport.LabelEntry(label.id().value(), label.facet().name(),
                        label.builtinKey().orElse(null), label.name().orElse(null), CatalogViews.displayName(label),
                        List.copyOf(label.aliases()), label.status().name(),
                        label.mergedInto().map(LabelId::value).orElse(null)))
                .toList();
        List<CatalogExport.FolderEntry> folderEntries = new ArrayList<>();
        appendFolders(tree, null, folderEntries);
        return new CatalogExport(labelEntries, folderEntries);
    }

    @Override
    public ArchiveHealth archiveHealth(Actor actor) {
        access.requireMember(actor);
        PaperlessArchive.ArchiveProbe probe;
        try {
            probe = archive.probe();
        } catch (ArchiveUnavailableException e) {
            probe = new PaperlessArchive.ArchiveProbe(false, null, e.getMessage());
        }
        Instant lastArchival = documents.findByState(ProcessingState.ARCHIVED).stream()
                .map(d -> d.archivedAt().orElse(null)).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        return new ArchiveHealth(probe.reachable(), clock.instant(), lastArchival);
    }

    @Override
    public Map<String, Long> processingCounts(Actor actor) {
        access.requireMember(actor);
        Map<ProcessingState, Long> counts = new EnumMap<>(ProcessingState.class);
        for (ProcessingState state : ProcessingState.values()) {
            counts.put(state, 0L);
        }
        for (Document document : documents.findAll()) {
            counts.merge(document.state(), 1L, Long::sum);
        }
        Map<String, Long> result = new LinkedHashMap<>();
        counts.forEach((state, count) -> result.put(state.name(), count));
        return result;
    }

    private Criteria criteria(DocumentSearchRequest request, CategoryTree tree, LabelVocabulary vocabulary) {
        Set<UUID> scope = null;
        if (request.categoryId() != null) {
            CategoryId folder = tree.require(new CategoryId(request.categoryId())).id();
            Set<CategoryId> selected = request.includeSubfolders() ? tree.subtree(folder) : Set.of(folder);
            scope = selected.stream().map(CategoryId::value).collect(java.util.stream.Collectors.toSet());
        }
        Map<Group, ValueFilter> filters = new EnumMap<>(Group.class);
        putLabels(filters, Group.DOCUMENT_TYPE, Facet.DOCUMENT_TYPE, request.documentTypes(), false, vocabulary);
        putLabels(filters, Group.ORGANIZATION, Facet.ORGANIZATION, request.organizations(), false, vocabulary);
        putLabels(filters, Group.TOPIC, Facet.TOPIC, request.topics(), request.topicsMatchAll(), vocabulary);
        putLabels(filters, Group.TAG, Facet.TAG, request.tags(), request.tagsMatchAll(), vocabulary);
        List<String> subjectKeys = Subjects.fromViews(request.subjects()).stream().map(Subjects::key).toList();
        if (!subjectKeys.isEmpty()) {
            filters.put(Group.SUBJECT, new ValueFilter(subjectKeys, false));
        }
        Set<Group> missing = EnumSet.noneOf(Group.class);
        for (String raw : request.missing()) {
            try {
                missing.add(Group.valueOf(raw));
            } catch (IllegalArgumentException | NullPointerException unknown) {
                throw new InvalidInputException("search.missing.invalid", "Unknown facet group " + raw);
            }
        }
        return new Criteria(null, scope, request.unsortedOnly(), filters, missing);
    }

    private static void putLabels(Map<Group, ValueFilter> filters, Group group, Facet facet, List<UUID> requested,
            boolean matchAll, LabelVocabulary vocabulary) {
        if (requested.isEmpty()) {
            return;
        }
        Set<String> values = new LinkedHashSet<>();
        for (UUID raw : requested) {
            Label label = vocabulary.resolve(new LabelId(raw));
            if (label.facet() != facet) {
                throw new InvalidInputException("search.facet.mismatch",
                        "Label " + label.id() + " does not belong to " + facet);
            }
            values.add(label.id().toString());
        }
        filters.put(group, new ValueFilter(List.copyOf(values), matchAll));
    }

    private FacetCounts facetCounts(Criteria criteria) {
        Map<String, Map<String, Long>> values = new LinkedHashMap<>();
        Map<String, Long> missing = new LinkedHashMap<>();
        for (Group group : Group.values()) {
            DocumentCatalog.GroupCounts counts = catalog.countGroup(criteria.withoutGroup(group), group);
            values.put(group.name(), counts.values());
            missing.put(group.name(), counts.missing());
        }
        return new FacetCounts(values, missing);
    }

    private static DocumentCatalog.Sort sort(String requested) {
        try {
            return DocumentCatalog.Sort.valueOf(requested);
        } catch (IllegalArgumentException | NullPointerException unknown) {
            throw new InvalidInputException("search.sort.invalid", "Unknown sort order " + requested);
        }
    }

    private static void appendFolders(CategoryTree tree, CategoryId parent,
            List<CatalogExport.FolderEntry> target) {
        for (Category folder : tree.childrenOf(parent)) {
            target.add(new CatalogExport.FolderEntry(folder.id().value(),
                    folder.parentId().map(CategoryId::value).orElse(null), folder.name(),
                    tree.path(folder.id()).stream().map(Category::name).toList(),
                    folder.impliedLabels().stream().map(LabelId::value).toList(),
                    Subjects.toViews(folder.impliedSubjects())));
            appendFolders(tree, folder.id(), target);
        }
    }

    private static DocumentExportRecord.Classification exportClassification(DocumentClassification classification,
            CategoryTree tree, LabelVocabulary vocabulary, PlacementSuggestion suggestion) {
        if (classification == null) {
            return new DocumentExportRecord.Classification(null, List.of(), List.of(),
                    DocumentExportRecord.Placement.NONE, null);
        }
        CategoryId categoryId = classification.categoryId().filter(id -> tree.find(id).isPresent()).orElse(null);
        List<String> path = categoryId == null ? List.of()
                : tree.path(categoryId).stream().map(Category::name).toList();
        List<DocumentExportRecord.Assignment> assignments = new ArrayList<>();
        for (LabelAssignment assignment : classification.assignments()) {
            Optional<Label> label = vocabulary.find(assignment.labelId());
            if (label.isEmpty()) {
                continue;
            }
            assignments.add(new DocumentExportRecord.Assignment(assignment.labelId().value(),
                    assignment.facet().name(), CatalogViews.displayName(label.get()), assignment.origin().name(),
                    assignment.state().name(),
                    assignment.confidence() == null ? null : assignment.confidence().value(),
                    assignment.sourceRef(), assignment.decidedBy(), assignment.decidedAt()));
        }
        DocumentClassification.Placement placement = classification.placement();
        return new DocumentExportRecord.Classification(categoryId == null ? null : categoryId.value(), path,
                assignments,
                new DocumentExportRecord.Placement(
                        placement.origin() == null ? null : placement.origin().name(),
                        placement.confidence() == null ? null : placement.confidence().value(),
                        placement.sourceRef(), placement.by(), placement.at(), placement.byMember()),
                exportSuggestion(suggestion, tree));
    }

    /** A pending proposal as a portable entry; {@code path} is its readable target for humans. */
    private static DocumentExportRecord.PlacementSuggestion exportSuggestion(PlacementSuggestion suggestion,
            CategoryTree tree) {
        if (suggestion == null) {
            return null;
        }
        PlacementTarget target = suggestion.target();
        return new DocumentExportRecord.PlacementSuggestion(
                target.folderId().map(CategoryId::value).orElse(null),
                target.anchorId().map(CategoryId::value).orElse(null), target.newPath(),
                target.readablePath(tree), suggestion.confidence(), suggestion.evidence(), suggestion.runRef(),
                suggestion.model(), suggestion.generation(), suggestion.createdAt());
    }

    private String friendlyFilename(Document document) {
        return AnalysisViews.filename(document, classificationViews.viewOf(document.id()),
                analyses.findById(document.id()).orElse(null), analysisSettings.filenameLanguage()).filename();
    }

    private OriginalContent originalOf(Document document, String filename) {
        if (document.state() == ProcessingState.ARCHIVED) {
            try {
                OriginalContent archived = archive.fetchOriginal(document.paperlessDocumentId().orElseThrow());
                if (!document.checksum().matches(archived.bytes())) {
                    throw new ArchiveUnavailableException("Archive original checksum mismatch");
                }
                return new OriginalContent(filename, document.contentType(), archived.bytes(),
                        OriginalContent.SOURCE_ARCHIVE);
            } catch (ArchiveUnavailableException unavailable) {
                if (!document.isStagingPresent()) {
                    throw new OriginalUnavailableException("archive unreachable and staging copy removed");
                }
            }
        }
        if (!document.isStagingPresent() || !store.exists(document.stagingPath())) {
            throw new OriginalUnavailableException("no copy of the original is available right now");
        }
        try {
            byte[] bytes = store.read(document.stagingPath());
            if (!document.checksum().matches(bytes)) {
                throw new OriginalUnavailableException("Staging original checksum mismatch");
            }
            return new OriginalContent(filename, document.contentType(), bytes, OriginalContent.SOURCE_STAGING);
        } catch (IOException e) {
            throw new OriginalUnavailableException("staging copy could not be read: " + e.getMessage());
        }
    }

    private Document load(DocumentId id) {
        return documents.findById(id).orElseThrow(() -> new NotFoundException("document", id.toString()));
    }
}
