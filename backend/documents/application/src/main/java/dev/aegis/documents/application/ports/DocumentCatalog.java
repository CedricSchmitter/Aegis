package dev.aegis.documents.application.ports;

import dev.aegis.shared.DocumentId;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Read model over the stored collection for filtering, counting and paging. Effective labels and subjects include
 * the values documents inherit from their folders. Implementations evaluate criteria in the database.
 */
public interface DocumentCatalog {

    /** Facet groups that can be filtered and counted. */
    enum Group {
        DOCUMENT_TYPE,
        ORGANIZATION,
        TOPIC,
        TAG,
        SUBJECT
    }

    enum Sort {
        NEWEST,
        OLDEST,
        TITLE
    }

    /** Title fragment plus archive full-text hits (Paperless ids); a document matches either. */
    record TextMatch(String titleFragment, List<Integer> paperlessIds) {
        public TextMatch {
            paperlessIds = List.copyOf(paperlessIds);
        }
    }

    /**
     * Selected values of one group: label ids as strings, or subject keys ({@code HOUSE} or
     * {@code MEMBER:<uuid>}). {@code matchAll} requires every value instead of any.
     */
    record ValueFilter(List<String> values, boolean matchAll) {
        public ValueFilter {
            values = List.copyOf(values);
        }
    }

    /**
     * {@code categoryScope} null means the whole collection; {@code unsortedOnly} selects documents without a
     * folder. {@code missing} selects documents without any effective value in a group.
     */
    record Criteria(TextMatch text, Set<UUID> categoryScope, boolean unsortedOnly, Map<Group, ValueFilter> filters,
            Set<Group> missing) {
        public Criteria {
            categoryScope = categoryScope == null ? null : Set.copyOf(categoryScope);
            filters = filters.isEmpty() ? Map.of() : Map.copyOf(new EnumMap<>(filters));
            missing = missing.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(missing));
        }

        public static Criteria everything() {
            return new Criteria(null, null, false, Map.of(), Set.of());
        }

        /** The same criteria without the filter and missing condition of {@code group} (disjunctive counting). */
        public Criteria withoutGroup(Group group) {
            Map<Group, ValueFilter> otherFilters = new EnumMap<>(Group.class);
            filters.forEach((g, f) -> {
                if (g != group) {
                    otherFilters.put(g, f);
                }
            });
            Set<Group> otherMissing = EnumSet.noneOf(Group.class);
            missing.stream().filter(g -> g != group).forEach(otherMissing::add);
            return new Criteria(text, categoryScope, unsortedOnly, otherFilters, otherMissing);
        }

        public Criteria withoutText() {
            return new Criteria(null, categoryScope, unsortedOnly, filters, missing);
        }
    }

    record Page(List<DocumentId> ids, long total) {
    }

    /** Document counts per value of a group and the number of documents without a value. */
    record GroupCounts(Map<String, Long> values, long missing) {
    }

    /** Documents with an own effective assignment and with a pending suggestion of a label. */
    record LabelUsage(long effective, long suggested) {
    }

    /** Matching document ids in the requested order and the total number of matches. */
    Page search(Criteria criteria, Sort sort, int offset, int limit);

    GroupCounts countGroup(Criteria criteria, Group group);

    /**
     * Archived documents matching {@code criteria} for which no classification run ever produced a result, oldest
     * upload first, at most {@code limit} ids (issue #115). Documents whose analysis is requested right now are
     * included; callers check their runs.
     */
    Page neverClassified(Criteria criteria, int limit);

    /** Matching documents that are not archived yet and therefore have no full text. */
    long countNotArchived(Criteria criteria);

    long countAll();

    /** Documents placed directly in each folder; folders without documents are absent. */
    Map<UUID, Long> documentsPerCategory();

    long countUnsorted();

    Map<UUID, LabelUsage> labelUsage();

    /** Ids of the documents in the trash; the counts above leave them out. */
    Set<UUID> trashedDocumentIds();
}
