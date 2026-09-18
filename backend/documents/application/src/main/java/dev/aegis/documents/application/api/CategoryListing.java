package dev.aegis.documents.application.api;

import java.util.List;

/** The whole folder tree plus the size of the Unsorted inbox and of the collection. */
public record CategoryListing(List<CategoryView> categories, long unsortedCount, long totalDocuments) {
}
