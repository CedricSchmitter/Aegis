package dev.aegis.documents.application;

import dev.aegis.documents.application.ports.CatalogLock;
import dev.aegis.documents.domain.Checksum;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.Subject;
import dev.aegis.documents.application.api.CategoryCommands.CreateCategory;
import dev.aegis.documents.application.api.CategoryView;
import dev.aegis.documents.application.api.LabelView;
import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Wires the catalog services against in-memory doubles so use-case tests stay fast and explicit. */
final class CatalogFixture {
    static final Instant NOW = Instant.parse("2026-09-17T10:00:00Z");

    final MemberId lisa = MemberId.random();
    final MemberId cedric = MemberId.random();
    final Actor actor = new Actor(lisa);
    final InMemoryDocumentRepository documents = new InMemoryDocumentRepository();
    final InMemoryClassificationRepository classifications = new InMemoryClassificationRepository();
    final InMemoryCategoryRepository categories = new InMemoryCategoryRepository();
    final InMemoryLabelRepository labels = new InMemoryLabelRepository();
    final InMemoryDocumentCatalog catalog = new InMemoryDocumentCatalog(documents, classifications, categories,
            labels);
    final TestDoubles.RecordingScheduler scheduler = new TestDoubles.RecordingScheduler();
    final CatalogLock lock = new NoOpCatalogLock();

    final InMemoryPlacementSuggestionRepository placements = new InMemoryPlacementSuggestionRepository();
    final ClassificationViews views = new ClassificationViews(classifications, categories, labels, placements);
    final DocumentPresenter presenter = TestDoubles.presenter(scheduler, views);
    final TestDoubles.MembersOnly access = new TestDoubles.MembersOnly(lisa, cedric);
    final TestDoubles.Persons persons = new TestDoubles.Persons(lisa, cedric);
    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    final InMemoryDocumentAnalysisRepository analyses = new InMemoryDocumentAnalysisRepository();
    final InMemoryDocumentHistoryRepository history = new InMemoryDocumentHistoryRepository();
    final DocumentHistoryRecorder recorder = recorder(analyses);

    final CategoryService categoryService = new CategoryService(categories, labels, classifications, documents,
            catalog, lock, persons, access, clock);
    final LabelService labelService = new LabelService(labels, categories, classifications, catalog, lock, access,
            clock);
    final ClassificationService classificationService = new ClassificationService(classifications, documents,
            categories, labels, placements, lock, presenter, access, clock, recorder);
    final PlacementService placementService = new PlacementService(classifications, placements, categories, labels,
            documents, lock, presenter, access, clock, recorder);
    final CatalogQueryService catalogQueries = new CatalogQueryService(categories, labels, catalog, access);
    final CatalogImportService importService = new CatalogImportService(labels, categories, classifications,
            placements, documents, lock, access, clock);

    /** A recorder over the given analyses, for tests that keep their own analysis repository. */
    DocumentHistoryRecorder recorder(InMemoryDocumentAnalysisRepository analysisRepository) {
        return new DocumentHistoryRecorder(documents, classifications, analysisRepository, history, clock);
    }

    CategoryView folder(String name, UUID parent) {
        return categoryService.create(actor, new CreateCategory(null, parent, name));
    }

    LabelView label(String facet, String name) {
        return labelService.create(actor, null, facet, name);
    }

    Document document(String title) {
        DocumentId id = DocumentId.random();
        Document document = Document.received(id, title, title + ".pdf", "application/pdf", 42,
                new Checksum(String.format("%064x", Math.abs(id.value().hashCode()))), lisa, NOW,
                Set.of(), id + ".pdf");
        documents.save(document);
        return document;
    }

    long categoryVersion(UUID id) {
        return catalogQueries.categories(actor).categories().stream().filter(c -> c.id().equals(id)).findFirst()
                .orElseThrow().version();
    }

    long labelVersion(UUID id) {
        return catalogQueries.labels(actor).stream().filter(l -> l.id().equals(id)).findFirst().orElseThrow()
                .version();
    }

    long classificationVersion(DocumentId id) {
        return classifications.findById(id).map(c -> c.version()).orElse(0L);
    }

    List<UUID> ids(LabelView... labelViews) {
        return java.util.Arrays.stream(labelViews).map(LabelView::id).toList();
    }
}
