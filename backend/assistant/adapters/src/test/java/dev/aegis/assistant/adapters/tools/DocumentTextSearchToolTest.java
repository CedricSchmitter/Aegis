package dev.aegis.assistant.adapters.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;

import dev.aegis.assistant.application.ports.AiTool;
import dev.aegis.assistant.domain.RunId;
import dev.aegis.documents.application.api.ArchivePdf;
import dev.aegis.documents.application.api.ContentSearchState;
import dev.aegis.documents.application.api.DocumentQueries;
import dev.aegis.documents.application.api.DocumentSearchRequest;
import dev.aegis.documents.application.api.DocumentSearchResult;
import dev.aegis.documents.application.api.DocumentSummary;
import dev.aegis.documents.application.api.DocumentThumbnail;
import dev.aegis.documents.application.api.FacetCounts;
import dev.aegis.documents.application.api.OriginalContent;
import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentTextSearchToolTest {
    private final List<DocumentSearchRequest> requests = new ArrayList<>();
    private DocumentSearchResult response;
    private final DocumentTextSearchTool tool = new DocumentTextSearchTool(new RecordingQueries());
    private final AiTool.Invocation invocation = new AiTool.Invocation(RunId.random(), new Actor(MemberId.random()));

    @Test
    void searchesTheWholeCollectionByWordsEvenWhenTheModelSendsFilters() {
        AiTool.Result result = tool.invoke(invocation, Map.of("query", " Kfz Versicherung ", "limit", 5,
                "folderId", UUID.randomUUID().toString(), "topicIds", List.of(UUID.randomUUID().toString())));

        assertThat(result.error()).isFalse();
        assertThat(result.content()).contains("\"contentSearch\":\"READY\"");
        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.text()).isEqualTo("Kfz Versicherung");
            assertThat(request.categoryId()).isNull();
            assertThat(request.unsortedOnly()).isFalse();
            assertThat(request.documentTypes()).isEmpty();
            assertThat(request.organizations()).isEmpty();
            assertThat(request.topics()).isEmpty();
            assertThat(request.tags()).isEmpty();
            assertThat(request.size()).isEqualTo(5);
        });
        assertThat(tool.spec().inputSchema()).extractingByKey("properties").asInstanceOf(MAP)
                .containsOnlyKeys("query", "sort", "page", "limit");
    }

    @Test
    void anInvalidSortIsReturnedToTheModel() {
        AiTool.Result result = tool.invoke(invocation, Map.of("query", "Strom", "sort", "RANDOM"));

        assertThat(result.error()).isTrue();
        assertThat(requests).isEmpty();
    }

    @Test
    void bothSearchToolsExposeCoverageInValidBoundedJson() {
        response = new DocumentSearchResult(List.of(), ContentSearchState.LIMITED, 1000, 0, 0, 20,
                FacetCounts.EMPTY, 0, 2, dev.aegis.documents.application.api.SearchLimitation.HIT_LIMIT, 700L,
                dev.aegis.documents.application.api.CountAccuracy.LOWER_BOUND);
        for (AiTool search : List.of(tool, new DocumentSearchTool(new RecordingQueries()))) {
            var result = search.invoke(invocation, Map.of("query", "clause"));
            var json = tools.jackson.databind.json.JsonMapper.builder().build().readTree(result.content());
            assertThat(result.retrievalIncomplete()).isTrue();
            assertThat(json.path("contentSearch").asString()).isEqualTo("LIMITED");
            assertThat(json.path("searchLimitation").asString()).isEqualTo("HIT_LIMIT");
            assertThat(json.path("archiveTotal").asLong()).isEqualTo(700);
            assertThat(json.path("archivalFailed").asLong()).isEqualTo(2);
            assertThat(json.path("matchingDocuments").asLong()).isZero();
            assertThat(json.path("countAccuracy").asString()).isEqualTo("LOWER_BOUND");
            assertThat(json.path("omittedDocuments").asInt()).isZero();
        }
    }

    @Test
    void oversizedMetadataOmitsWholeDocumentsAndRetainsCoverageAsValidJson() {
        DocumentSummary large = new DocumentSummary(DocumentId.random(), "x".repeat(5000), "MANUAL", "scan.pdf",
                "application/pdf", 10, "checksum", MemberId.random(), java.time.Instant.EPOCH, List.of(),
                new dev.aegis.documents.application.api.ProcessingInfo("ARCHIVED", 0, null, null, null, false, null, 1, java.time.Instant.EPOCH), true, 0,
                dev.aegis.documents.application.api.ClassificationView.UNSORTED, null, null, null, false, null, null);
        response = new DocumentSearchResult(java.util.Collections.nCopies(20, large), ContentSearchState.READY,
                20, 20, 0, 20, FacetCounts.EMPTY, 0);
        for (AiTool search : List.of(tool, new DocumentSearchTool(new RecordingQueries()))) {
            var result = search.invoke(invocation, Map.of("query", "clause"));
            var json = tools.jackson.databind.json.JsonMapper.builder().build().readTree(result.content());
            assertThat(result.content().length()).isLessThanOrEqualTo(18_000);
            assertThat(json.path("documents").size() + json.path("omittedDocuments").asInt()).isEqualTo(20);
            assertThat(json.path("documents").size()).isPositive().isLessThan(20);
            assertThat(json.path("countAccuracy").asString()).isEqualTo("EXACT");
            assertThat(result.retrievalIncomplete()).isTrue();
        }
    }

    private final class RecordingQueries implements DocumentQueries {
        @Override
        public DocumentSearchResult search(Actor actor, DocumentSearchRequest request) {
            requests.add(request);
            if (response != null) return response;
            return new DocumentSearchResult(List.of(), ContentSearchState.READY, 0, 0, request.page(),
                    request.size(), FacetCounts.EMPTY, 0);
        }

        @Override
        public DocumentSummary get(Actor actor, DocumentId id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<DocumentSummary> trashed(Actor actor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public OriginalContent original(Actor actor, DocumentId id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ArchivePdf archivePdf(Actor actor, DocumentId id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DocumentThumbnail thumbnail(Actor actor, DocumentId id) {
            throw new UnsupportedOperationException();
        }
    }
}
