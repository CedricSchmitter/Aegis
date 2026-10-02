package dev.aegis.assistant.adapters.tools;

import dev.aegis.assistant.application.JsonText;
import dev.aegis.assistant.application.UntrustedContent;
import dev.aegis.assistant.application.chat.ChatTools;
import dev.aegis.assistant.application.ports.AiTool;
import dev.aegis.assistant.application.ports.ToolSpec;
import dev.aegis.contracts.application.api.ContractQueries;
import dev.aegis.documents.application.api.CategoryRef;
import dev.aegis.documents.application.api.ContentUnavailableException;
import dev.aegis.documents.application.api.DocumentQueries;
import dev.aegis.documents.application.api.DocumentSummary;
import dev.aegis.documents.application.api.DocumentTextQuery;
import dev.aegis.documents.application.api.DocumentTextQuery.DocumentText;
import dev.aegis.shared.DocumentId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Reads the archived text of one document in parts, framed as untrusted data, with its metadata and the contracts
 * that cite it. The archive separates pages with form feeds; they are shown as page markers so the model can say
 * where a statement stands.
 */
@Component
public class ReadDocumentTool implements AiTool {
    static final int PART = 12_000;
    /** How far a part may end early to end at a line or word boundary instead of inside a word. */
    static final int BOUNDARY_WINDOW = 400;
    private static final char PAGE_BREAK = '\f';

    private static final ToolSpec SPEC = new ToolSpec(ChatTools.READ_DOCUMENT,
            "Reads the text the archive recognized in one document, in parts of about " + PART + " characters, "
                    + "with title, folder, labels, document date and the contracts that cite it. Page markers such "
                    + "as '--- page 2 ---' separate pages when the archive knows them; they are not part of the "
                    + "text. Read the next part with offset = nextOffset.",
            Map.of("type", "object",
                    "properties", Map.of(
                            "id", Map.of("type", "string", "description", "Document id from search_documents"),
                            "offset", Map.of("type", "integer",
                                    "description", "Character offset of the part, 0 for the beginning")),
                    "required", List.of("id"),
                    "additionalProperties", false));

    private final DocumentTextQuery texts;
    private final DocumentQueries documents;
    private final ContractQueries contracts;

    public ReadDocumentTool(DocumentTextQuery texts, DocumentQueries documents, ContractQueries contracts) {
        this.texts = texts;
        this.documents = documents;
        this.contracts = contracts;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public Result invoke(Invocation invocation, Map<String, Object> input) {
        UUID id;
        int offset;
        try {
            id = ToolInput.requiredUuid(input, "id");
            offset = ToolInput.integer(input, "offset", 0, Integer.MAX_VALUE, 0);
        } catch (IllegalArgumentException invalid) {
            return ToolInput.invalid(invalid.getMessage());
        }
        DocumentId documentId = new DocumentId(id);
        DocumentText text;
        try {
            text = texts.text(invocation.actor(), documentId);
        } catch (ContentUnavailableException unavailable) {
            return Result.error(JsonText.write(Map.of("error", "document.textUnavailable",
                    "detail", "The archive cannot deliver the text right now.")));
        }
        DocumentSummary summary = documents.get(invocation.actor(), documentId);
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("id", id.toString());
        answer.put("title", text.title());
        answer.put("uploadedAt", summary.uploadedAt().toString());
        if (summary.analysis() != null && summary.analysis().documentDate() != null) {
            answer.put("documentDate", summary.analysis().documentDate().toString());
        }
        answer.put("folder", summary.classification().path().stream().map(CategoryRef::name).toList());
        answer.put("labels", summary.classification().labels().stream().map(DocumentSearchTool::label).toList());
        answer.put("contracts", contracts.forDocument(invocation.actor(), documentId).stream()
                .map(contract -> Map.of("id", contract.id().toString(), "name", contract.name())).toList());
        String content = text.text();
        answer.put("totalCharacters", content.length());
        if (content.isBlank()) {
            answer.put("textRecognized", false);
            answer.put("note", "The archive recognized no text in this document, for example a photo it could "
                    + "not read. Its content is unknown.");
            return Result.ok(JsonText.write(answer));
        }
        boolean paged = content.indexOf(PAGE_BREAK) >= 0;
        if (paged) {
            answer.put("pages", count(content, content.length()) + 1);
        }
        int start = Math.min(offset, content.length());
        int end = end(content, start);
        answer.put("offset", start);
        answer.put("nextOffset", end < content.length() ? end : null);
        answer.put("text", UntrustedContent.wrap("document", id.toString(), render(content, start, end, paged),
                Integer.MAX_VALUE));
        return Result.ok(JsonText.write(answer));
    }

    /** The end of the part: at most {@link #PART} characters, preferably after a line break or blank. */
    static int end(String content, int start) {
        int limit = Math.min(content.length(), start + PART);
        if (limit == content.length()) {
            return limit;
        }
        for (int i = limit; i > limit - BOUNDARY_WINDOW && i > start; i--) {
            if (content.charAt(i - 1) == '\n' || content.charAt(i - 1) == PAGE_BREAK) {
                return i;
            }
        }
        for (int i = limit; i > limit - BOUNDARY_WINDOW && i > start; i--) {
            if (Character.isWhitespace(content.charAt(i - 1))) {
                return i;
            }
        }
        return limit;
    }

    static String render(String content, int start, int end, boolean paged) {
        if (!paged) {
            return content.substring(start, end);
        }
        int page = count(content, start) + 1;
        StringBuilder out = new StringBuilder("--- page ").append(page).append(" ---\n");
        for (int i = start; i < end; i++) {
            char c = content.charAt(i);
            if (c == PAGE_BREAK) {
                page++;
                out.append("\n--- page ").append(page).append(" ---\n");
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static int count(String content, int before) {
        int breaks = 0;
        for (int i = 0; i < before; i++) {
            if (content.charAt(i) == PAGE_BREAK) {
                breaks++;
            }
        }
        return breaks;
    }
}
