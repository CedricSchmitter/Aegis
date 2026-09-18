package dev.aegis.documents.application.api;

import java.util.List;

/**
 * Search response distinguishing an empty collection from an unavailable archive. {@code totalDocuments} is the
 * size of the whole collection, {@code matchingDocuments} the number of results across all pages, and
 * {@code notContentIndexed} the number of otherwise matching documents that are not archived yet and therefore
 * cannot match by content.
 */
public record DocumentSearchResult(List<DocumentSummary> items, ContentSearchState contentSearch, long totalDocuments,
        long matchingDocuments, int page, int size, FacetCounts facets, long notContentIndexed) {
}
