package dev.aegis.documents.application.api;

import java.util.List;

/**
 * totalDocuments counts the active Aegis collection. matchingDocuments and facets count the collected archive hits
 * plus title matches after Aegis filters, and are lower bounds when traversal is incomplete. archiveTotal is a
 * separate unfiltered archive count, never an Aegis count. notContentIndexed counts matching unarchived documents;
 * even complete archive traversal cannot establish semantic recall or the contents of those documents.
 */
public record DocumentSearchResult(List<DocumentSummary> items, ContentSearchState contentSearch, long totalDocuments,
        long matchingDocuments, int page, int size, FacetCounts facets, long notContentIndexed,
        SearchLimitation searchLimitation, Long archiveTotal, CountAccuracy countAccuracy) {
    public DocumentSearchResult(List<DocumentSummary> items, ContentSearchState contentSearch, long totalDocuments,
            long matchingDocuments, int page, int size, FacetCounts facets, long notContentIndexed) {
        this(items, contentSearch, totalDocuments, matchingDocuments, page, size, facets, notContentIndexed,
                contentSearch == ContentSearchState.UNAVAILABLE ? SearchLimitation.ARCHIVE_FAILURE : null, null,
                contentSearch == ContentSearchState.UNAVAILABLE || contentSearch == ContentSearchState.LIMITED
                        || notContentIndexed > 0
                        ? CountAccuracy.LOWER_BOUND : CountAccuracy.EXACT);
    }
}
