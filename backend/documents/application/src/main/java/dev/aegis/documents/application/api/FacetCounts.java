package dev.aegis.documents.application.api;

import java.util.Map;

/**
 * Result counts per facet group, each computed with the filters of all other groups applied. {@code values} maps a
 * group name to value keys (label ids, or subject keys {@code HOUSE}, {@code HOUSEHOLD}, {@code MEMBER:<id>}) and
 * their document counts; {@code missing} holds the count of documents without a value in the group.
 */
public record FacetCounts(Map<String, Map<String, Long>> values, Map<String, Long> missing) {
    public static final FacetCounts EMPTY = new FacetCounts(Map.of(), Map.of());
}
