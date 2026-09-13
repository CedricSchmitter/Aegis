package dev.aegis.documents.application.api;

import java.util.List;

/** Search response distinguishing an empty collection from an unavailable archive. */
public record DocumentSearchResult(List<DocumentSummary> items, ContentSearchState contentSearch, long totalDocuments) {
}
