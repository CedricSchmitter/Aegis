package dev.aegis.documents.application.api;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Structured search over the shared collection. Groups combine with AND, values inside a group with OR (topics and
 * tags optionally with ALL). {@code categoryId} limits the scope to a folder (with its subfolders when
 * {@code includeSubfolders}); {@code unsortedOnly} selects documents without a folder. {@code missing} holds facet
 * group names ({@code DOCUMENT_TYPE}, {@code ORGANIZATION}, {@code TOPIC}, {@code TAG}, {@code SUBJECT}) whose value
 * must be absent. {@code sort} is {@code NEWEST}, {@code OLDEST} or {@code TITLE}.
 */
public record DocumentSearchRequest(String text, UUID categoryId, boolean includeSubfolders, boolean unsortedOnly,
        List<UUID> documentTypes, List<UUID> organizations, List<UUID> topics, List<UUID> tags,
        List<SubjectView> subjects, boolean topicsMatchAll, boolean tagsMatchAll, Set<String> missing, String sort,
        int page, int size) {
    public static final int DEFAULT_SIZE = 25;
    public static final int MAX_SIZE = 100;

    public DocumentSearchRequest {
        documentTypes = documentTypes == null ? List.of() : List.copyOf(documentTypes);
        organizations = organizations == null ? List.of() : List.copyOf(organizations);
        topics = topics == null ? List.of() : List.copyOf(topics);
        tags = tags == null ? List.of() : List.copyOf(tags);
        subjects = subjects == null ? List.of() : List.copyOf(subjects);
        missing = missing == null ? Set.of() : Set.copyOf(missing);
        sort = sort == null || sort.isBlank() ? "NEWEST" : sort;
        page = Math.max(0, page);
        size = size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
    }

    /** Text-only search over the whole collection, newest first. */
    public static DocumentSearchRequest text(String text) {
        return new DocumentSearchRequest(text, null, true, false, null, null, null, null, null, false, false, null,
                null, 0, MAX_SIZE);
    }
}
