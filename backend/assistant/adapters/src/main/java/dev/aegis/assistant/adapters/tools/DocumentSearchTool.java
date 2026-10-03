package dev.aegis.assistant.adapters.tools;

import dev.aegis.assistant.application.JsonText;
import dev.aegis.assistant.application.chat.ChatTools;
import dev.aegis.assistant.application.ports.AiTool;
import dev.aegis.assistant.application.ports.ToolSpec;
import dev.aegis.documents.application.api.CategoryRef;
import dev.aegis.documents.application.api.DocumentQueries;
import dev.aegis.documents.application.api.DocumentSearchRequest;
import dev.aegis.documents.application.api.DocumentSearchResult;
import dev.aegis.documents.application.api.DocumentSummary;
import dev.aegis.documents.application.api.LabelChip;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Read-only document search for the model through the documents context's structured search, with the run's member,
 * so the same household authorization and the same matching apply as in the web app: words match title and archived
 * text; label groups combine with AND, the labels of one group with OR unless ALL is asked for. Returns metadata only;
 * document content is read with {@code read_document}.
 */
@Component
public class DocumentSearchTool implements AiTool {
    public static final String NAME = ChatTools.SEARCH_DOCUMENTS;
    static final int MAX_QUERY = 200;
    static final int MAX_RESULTS = 20;
    static final int MAX_IDS = 20;
    private static final Set<String> SORTS = Set.of("NEWEST", "OLDEST", "TITLE");

    private static final ToolSpec SPEC = new ToolSpec(NAME,
            "Searches the shared household documents. Words match the title and the archived full text. Folder and "
                    + "label ids come from household_catalog: label groups combine with AND, several labels of one "
                    + "group with OR (topics and tags can require ALL). Labels can be missing or wrong, so search "
                    + "with words only as well when a filtered search finds little. Returns metadata only: id, "
                    + "title, upload time, document date, folder path, labels and whether the archive recognized "
                    + "text. Never returns document content.",
            Map.of("type", "object",
                    "properties", Map.ofEntries(
                            Map.entry("query", Map.of("type", "string",
                                    "description", "Search words; an empty string matches every document")),
                            Map.entry("folderId", Map.of("type", "string",
                                    "description", "Only documents in this folder")),
                            Map.entry("includeSubfolders", Map.of("type", "boolean",
                                    "description", "With folderId: include subfolders (default true)")),
                            Map.entry("unsortedOnly", Map.of("type", "boolean",
                                    "description", "Only documents without a folder")),
                            Map.entry("documentTypeIds", ids("Document type label ids (any of them)")),
                            Map.entry("organizationIds", ids("Organization label ids (any of them)")),
                            Map.entry("topicIds", ids("Topic label ids")),
                            Map.entry("tagIds", ids("Tag label ids")),
                            Map.entry("topicsMatchAll", Map.of("type", "boolean",
                                    "description", "Require all topicIds instead of any")),
                            Map.entry("tagsMatchAll", Map.of("type", "boolean",
                                    "description", "Require all tagIds instead of any")),
                            Map.entry("sort", Map.of("type", "string", "enum", List.copyOf(SORTS))),
                            Map.entry("page", Map.of("type", "integer", "description", "Result page from 0")),
                            Map.entry("limit", Map.of("type", "integer",
                                    "description", "Results per page, 1 to " + MAX_RESULTS))),
                    "required", List.of("query"),
                    "additionalProperties", false));

    private final DocumentQueries documents;

    public DocumentSearchTool(DocumentQueries documents) {
        this.documents = documents;
    }

    private static Map<String, Object> ids(String description) {
        return Map.of("type", "array", "items", Map.of("type", "string"), "description", description);
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public Result invoke(Invocation invocation, Map<String, Object> input) {
        DocumentSearchRequest request;
        try {
            request = request(input);
        } catch (IllegalArgumentException invalid) {
            return ToolInput.invalid(invalid.getMessage());
        }
        return Result.ok(JsonText.write(answer(documents.search(invocation.actor(), request))));
    }

    static Map<String, Object> answer(DocumentSearchResult result) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (DocumentSummary document : result.items()) {
            items.add(item(document));
        }
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("contentSearch", result.contentSearch().name());
        answer.put("matchingDocuments", result.matchingDocuments());
        answer.put("page", result.page());
        answer.put("notContentIndexed", result.notContentIndexed());
        answer.put("documents", items);
        return answer;
    }

    private static DocumentSearchRequest request(Map<String, Object> input) {
        String query = ToolInput.text(input, "query", MAX_QUERY, true);
        UUID folder = ToolInput.uuid(input, "folderId");
        return new DocumentSearchRequest(query.strip(), folder,
                ToolInput.flag(input, "includeSubfolders", true), ToolInput.flag(input, "unsortedOnly", false),
                ToolInput.uuids(input, "documentTypeIds", MAX_IDS), ToolInput.uuids(input, "organizationIds", MAX_IDS),
                ToolInput.uuids(input, "topicIds", MAX_IDS), ToolInput.uuids(input, "tagIds", MAX_IDS), null,
                ToolInput.flag(input, "topicsMatchAll", false), ToolInput.flag(input, "tagsMatchAll", false), null,
                sort(input), page(input), limit(input));
    }

    static String sort(Map<String, Object> input) {
        String sort = ToolInput.text(input, "sort", 10, false);
        if (sort != null && !SORTS.contains(sort)) {
            throw new IllegalArgumentException("sort must be NEWEST, OLDEST or TITLE");
        }
        return sort;
    }

    static int page(Map<String, Object> input) {
        return ToolInput.integer(input, "page", 0, 1_000, 0);
    }

    static int limit(Map<String, Object> input) {
        return ToolInput.integer(input, "limit", 1, MAX_RESULTS, 10);
    }

    static Map<String, Object> item(DocumentSummary document) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", document.id().toString());
        item.put("title", document.title());
        item.put("uploadedAt", document.uploadedAt().toString());
        if (document.analysis() != null && document.analysis().documentDate() != null) {
            item.put("documentDate", document.analysis().documentDate().toString());
        }
        item.put("archiveState", document.processing().state());
        if (document.textRecognition() != null) {
            item.put("textRecognized", document.textRecognition().state());
        }
        item.put("folder", document.classification().path().stream().map(CategoryRef::name).toList());
        item.put("labels", document.classification().labels().stream().map(DocumentSearchTool::label).toList());
        return item;
    }

    static Map<String, Object> label(LabelChip chip) {
        Map<String, Object> label = new LinkedHashMap<>();
        label.put("id", chip.labelId() == null ? null : chip.labelId().toString());
        label.put("facet", chip.facet());
        label.put("name", chip.builtinKey() != null && chip.name() == null ? chip.builtinKey() : chip.name());
        label.put("reviewState", chip.reviewState());
        return label;
    }
}
