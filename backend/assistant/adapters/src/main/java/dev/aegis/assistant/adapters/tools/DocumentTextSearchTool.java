package dev.aegis.assistant.adapters.tools;

import dev.aegis.assistant.application.JsonText;
import dev.aegis.assistant.application.chat.ChatTools;
import dev.aegis.assistant.application.ports.AiTool;
import dev.aegis.assistant.application.ports.ToolSpec;
import dev.aegis.documents.application.api.DocumentQueries;
import dev.aegis.documents.application.api.DocumentSearchRequest;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The document search of {@link DocumentSearchTool} without folder and label filters, for the assistant's
 * {@code TEXT_ONLY} retrieval: documents are found by words in their title and archived text alone. The results are
 * the same metadata as in the filtered search.
 */
@Component
public class DocumentTextSearchTool implements AiTool {
    private static final ToolSpec SPEC = new ToolSpec(ChatTools.SEARCH_DOCUMENT_TEXT,
            "Searches the shared household documents by words. Words match the title and the archived full text. "
                    + "Returns metadata only: id, title, upload time, document date, folder path, labels and whether "
                    + "the archive recognized text. Never returns document content. contentSearch READY means archive traversal completed; LIMITED or UNAVAILABLE means missing sources. matchingDocuments and facets can be lower bounds. Narrow or retry; never infer absence or exhaustive coverage from incomplete search.",
            Map.of("type", "object",
                    "properties", Map.of(
                            "query", Map.of("type", "string",
                                    "description", "Search words; an empty string matches every document"),
                            "sort", Map.of("type", "string", "enum", List.of("NEWEST", "OLDEST", "TITLE")),
                            "page", Map.of("type", "integer", "description", "Result page from 0"),
                            "limit", Map.of("type", "integer",
                                    "description", "Results per page, 1 to " + DocumentSearchTool.MAX_RESULTS)),
                    "required", List.of("query"),
                    "additionalProperties", false));

    private final DocumentQueries documents;

    public DocumentTextSearchTool(DocumentQueries documents) {
        this.documents = documents;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public Result invoke(Invocation invocation, Map<String, Object> input) {
        DocumentSearchRequest request;
        try {
            request = new DocumentSearchRequest(
                    ToolInput.text(input, "query", DocumentSearchTool.MAX_QUERY, true).strip(), null, true, false,
                    null, null, null, null, null, false, false, null, DocumentSearchTool.sort(input),
                    DocumentSearchTool.page(input), DocumentSearchTool.limit(input));
        } catch (IllegalArgumentException invalid) {
            return ToolInput.invalid(invalid.getMessage());
        }
        return DocumentSearchTool.response(documents.search(invocation.actor(), request));
    }
}
