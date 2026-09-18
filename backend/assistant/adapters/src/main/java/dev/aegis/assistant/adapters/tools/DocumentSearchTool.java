package dev.aegis.assistant.adapters.tools;

import dev.aegis.assistant.application.JsonText;
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
import org.springframework.stereotype.Component;

/**
 * Read-only document search for the model, through the documents context's public query contract with the run's
 * member, so the same household authorization applies as in the UI. Returns metadata only; document content is not
 * exposed by this tool.
 */
@Component
public class DocumentSearchTool implements AiTool {
    public static final String NAME = "search_documents";
    static final int MAX_QUERY = 200;
    static final int MAX_RESULTS = 10;

    private static final ToolSpec SPEC = new ToolSpec(NAME,
            "Searches the shared household documents by title and archived full text. Returns metadata only: id, "
                    + "title, upload time, folder path, labels and archive state. Never returns document content.",
            Map.of("type", "object",
                    "properties", Map.of(
                            "query", Map.of("type", "string",
                                    "description", "Search words; an empty string lists the newest documents"),
                            "limit", Map.of("type", "integer", "description", "Maximum number of results, 1 to 10")),
                    "required", List.of("query"),
                    "additionalProperties", false));

    private final DocumentQueries documents;

    public DocumentSearchTool(DocumentQueries documents) {
        this.documents = documents;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public Result invoke(Invocation invocation, Map<String, Object> input) {
        if (!(input.get("query") instanceof String query) || query.length() > MAX_QUERY
                || (input.containsKey("limit") && !(input.get("limit") instanceof Number))) {
            return Result.error(JsonText.write(Map.of("error", "input.invalid",
                    "detail", "query must be a string of at most " + MAX_QUERY + " characters")));
        }
        int limit = input.get("limit") instanceof Number number
                ? Math.max(1, Math.min(MAX_RESULTS, number.intValue())) : 5;
        DocumentSearchResult result = documents.search(invocation.actor(), new DocumentSearchRequest(query.strip(),
                null, true, false, null, null, null, null, null, false, false, null, "NEWEST", 0, limit));
        List<Map<String, Object>> items = new ArrayList<>();
        for (DocumentSummary document : result.items()) {
            items.add(item(document));
        }
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("contentSearch", result.contentSearch().name());
        answer.put("matchingDocuments", result.matchingDocuments());
        answer.put("documents", items);
        return Result.ok(JsonText.write(answer));
    }

    private static Map<String, Object> item(DocumentSummary document) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", document.id().toString());
        item.put("title", document.title());
        item.put("uploadedAt", document.uploadedAt().toString());
        item.put("archiveState", document.processing().state());
        item.put("folder", document.classification().path().stream().map(CategoryRef::name).toList());
        item.put("labels", document.classification().labels().stream().map(DocumentSearchTool::label).toList());
        return item;
    }

    private static Map<String, Object> label(LabelChip chip) {
        Map<String, Object> label = new LinkedHashMap<>();
        label.put("facet", chip.facet());
        label.put("name", chip.builtinKey() != null && chip.name() == null ? chip.builtinKey() : chip.name());
        label.put("reviewState", chip.reviewState());
        return label;
    }
}
