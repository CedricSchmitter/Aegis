package dev.aegis.documents.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import dev.aegis.documents.application.api.*;
import dev.aegis.documents.application.ports.ArchiveSearchResult;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.ProcessingFailure;
import dev.aegis.shared.*;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RetrievalCoverageTest {
    private final CatalogFixture f = new CatalogFixture();
    private final TestDoubles.FakeArchive archive = new TestDoubles.FakeArchive();
    private final DocumentQueryService queries = new DocumentQueryService(f.documents, archive,
            new InMemoryOriginalStore(), f.presenter, f.catalog, f.categories, f.labels, f.classifications, f.views,
            f.placements, new InMemoryDocumentAnalysisRepository(), TestDoubles.analysisSettings(), f.access, f.clock);

    private Document archived(String title, int id) {
        Document d = f.document(title); d.markArchived(id, CatalogFixture.NOW); f.documents.save(d); return d;
    }

    @Test
    void filtersFindContentOnlyLateSourceAndArchiveTotalIsNeverTheFilteredCount() {
        var folder = f.folder("Synthetic contracts", null);
        var label = f.label("TOPIC", "Synthetic topic");
        Document late = archived("Scan 0201", 201);
        archived("Distractor", 1);
        f.classificationService.place(f.actor, late.id(), folder.id(), 0);
        f.classificationService.setLabels(f.actor, late.id(), List.of(label.id()), f.classificationVersion(late.id()));
        archive.searchResult = ArchiveSearchResult.complete(IntStream.rangeClosed(1, 500).boxed().toList());
        var request = new DocumentSearchRequest("late clause", folder.id(), true, false, null, null,
                List.of(label.id()), null, null, false, false, Set.of(), "TITLE", 0, 25);
        for (Actor member : List.of(f.actor, new Actor(f.cedric))) {
            var result = queries.search(member, request);
            assertThat(result.items()).extracting(DocumentSummary::id).containsExactly(late.id());
            assertThat(result.archiveTotal()).isEqualTo(500L);
            assertThat(result.matchingDocuments()).isEqualTo(1);
            assertThat(result.countAccuracy()).isEqualTo(CountAccuracy.EXACT);
            assertThat(result.facets().values().get("TOPIC")).containsEntry(label.id().toString(), 1L);
        }
        assertThatThrownBy(() -> queries.search(new Actor(MemberId.random()), request))
                .isInstanceOf(NotAuthorizedException.class);
        late.moveToTrash(f.lisa, CatalogFixture.NOW, Trash.RecoveryWindow.DEFAULT);
        f.documents.save(late);
        assertThat(queries.search(f.actor, request).items()).isEmpty();
        assertThat(queries.search(f.actor, request).matchingDocuments()).isZero();
    }

    @Test
    void partialTraversalKeepsTitleMatchesButCountsAndFacetsAreLowerBounds() {
        archived("late clause title", 2);
        Document content = archived("Scan", 1);
        archive.searchResult = new ArchiveSearchResult(List.of(1), ContentSearchState.LIMITED,
                SearchLimitation.ARCHIVE_FAILURE, 500L);
        var result = queries.search(f.actor, "late clause");
        assertThat(result.items()).extracting(DocumentSummary::id).contains(content.id());
        assertThat(result.matchingDocuments()).isEqualTo(2);
        assertThat(result.countAccuracy()).isEqualTo(CountAccuracy.LOWER_BOUND);
        assertThat(result.archiveTotal()).isEqualTo(500L);
        assertThat(result.contentSearch()).isEqualTo(ContentSearchState.LIMITED);
        assertThat(result.searchLimitation()).isEqualTo(SearchLimitation.ARCHIVE_FAILURE);
        archive.available = false;
        var fallback = queries.search(f.actor, "late clause");
        assertThat(fallback.items()).extracting(DocumentSummary::title).containsExactly("late clause title");
        assertThat(fallback.contentSearch()).isEqualTo(ContentSearchState.UNAVAILABLE);
        assertThat(fallback.countAccuracy()).isEqualTo(CountAccuracy.LOWER_BOUND);
        assertThat(fallback.archiveTotal()).isNull();
    }

    @Test
    void unarchivedSourcesAlsoPreventExactFullTextCountsButEmptyQueryDoesNotCallArchive() {
        f.document("Pending upload");
        var result = queries.search(f.actor, "clause");
        assertThat(result.countAccuracy()).isEqualTo(CountAccuracy.LOWER_BOUND);
        assertThat(result.notContentIndexed()).isEqualTo(1);
        archive.available = false;
        var list = queries.search(f.actor, "");
        assertThat(list.contentSearch()).isEqualTo(ContentSearchState.NOT_APPLIED);
        assertThat(list.countAccuracy()).isEqualTo(CountAccuracy.EXACT);
        assertThat(list.matchingDocuments()).isEqualTo(1);
    }

    @Test
    void failedArchivalIsReportedOnItsOwnAndLeavesCountsExact() {
        archived("Clause", 1);
        Document failed = f.document("Failed upload");
        failed.recordFailure(new ProcessingFailure("paperless.rejected", "rejected", CatalogFixture.NOW), false, 5);
        f.documents.save(failed);
        archive.searchResult = ArchiveSearchResult.complete(List.of(1));
        var result = queries.search(f.actor, "clause");
        assertThat(result.archivalFailed()).isEqualTo(1);
        assertThat(result.notContentIndexed()).isZero();
        assertThat(result.countAccuracy()).isEqualTo(CountAccuracy.EXACT);
        assertThat(queries.search(f.actor, "").archivalFailed()).isZero();
    }
}
