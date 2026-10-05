package dev.aegis.documents.application.ports;

import dev.aegis.documents.application.api.ContentSearchState;
import dev.aegis.documents.application.api.SearchLimitation;
import java.util.List;
import java.util.Objects;

/** Collected archive IDs, traversal state and the archive's unfiltered total when known. */
public record ArchiveSearchResult(List<Integer> ids, ContentSearchState state, SearchLimitation limitation,
        Long archiveTotal) {
    public ArchiveSearchResult {
        ids = List.copyOf(ids);
        Objects.requireNonNull(state);
        if (state == ContentSearchState.NOT_APPLIED || (state == ContentSearchState.READY) != (limitation == null)
                || (archiveTotal != null && archiveTotal < 0)) {
            throw new IllegalArgumentException("Invalid archive search coverage");
        }
    }

    public static ArchiveSearchResult complete(List<Integer> ids) {
        return new ArchiveSearchResult(ids, ContentSearchState.READY, null, (long) ids.size());
    }
}
