package dev.aegis.assistant.adapters.tools;

import dev.aegis.assistant.application.JsonText;
import dev.aegis.assistant.application.UntrustedContent;
import dev.aegis.assistant.application.chat.ChatTools;
import dev.aegis.assistant.application.chat.PassageSearch;
import dev.aegis.assistant.application.chat.PassageSearch.Passage;
import dev.aegis.assistant.application.ports.AiTool;
import dev.aegis.assistant.application.ports.ToolSpec;
import dev.aegis.documents.application.api.ContentUnavailableException;
import dev.aegis.documents.application.api.DocumentTextQuery;
import dev.aegis.documents.application.api.DocumentTextQuery.DocumentText;
import dev.aegis.shared.DocumentId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Finds the passages of one document's archived text that best match a few words ({@link PassageSearch}), through the
 * same {@link DocumentTextQuery} as {@code read_document}, so the same authorization, trash rules and text cache
 * apply. Each passage is framed as untrusted content with page markers and names the offset from which
 * {@code read_document} continues.
 */
@Component
public class FindInDocumentTool implements AiTool {
    static final int MAX_QUERY = 200;
    static final int MAX_PASSAGES = 8;
    /** Below the executor's 20,000-character tool result boundary, so the result stays valid JSON. */
    static final int MAX_JSON = 18_000;

    private static final ToolSpec SPEC = new ToolSpec(ChatTools.FIND_IN_DOCUMENT,
            "Searches the text of one document for words and returns the passages that contain the most of them, "
                    + "best first, each with about " + PassageSearch.CONTEXT + " characters of context, its page "
                    + "when known and the offset at which read_document continues. Use it to find a clause, amount, "
                    + "date or number in a long document instead of reading it part by part. Case, accents, umlauts "
                    + "and ß/ss do not matter; words of four or more letters also match inside longer words; words "
                    + "with digits, such as contract numbers, match with or without separators. Give up to "
                    + PassageSearch.MAX_TERMS + " words, including synonyms the document may use. No passage only "
                    + "means these words do not occur; the document can still say it in other words.",
            Map.of("type", "object",
                    "properties", Map.of(
                            "id", Map.of("type", "string", "description", "Document id from a search"),
                            "query", Map.of("type", "string", "description", "Search words separated by blanks"),
                            "limit", Map.of("type", "integer",
                                    "description", "Passages to return, 1 to " + MAX_PASSAGES + " (default 5)")),
                    "required", List.of("id", "query"),
                    "additionalProperties", false));

    private final DocumentTextQuery texts;

    public FindInDocumentTool(DocumentTextQuery texts) {
        this.texts = texts;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public Result invoke(Invocation invocation, Map<String, Object> input) {
        UUID id;
        List<PassageSearch.Term> terms;
        int limit;
        try {
            id = ToolInput.requiredUuid(input, "id");
            terms = PassageSearch.terms(ToolInput.text(input, "query", MAX_QUERY, true));
            limit = ToolInput.integer(input, "limit", 1, MAX_PASSAGES, 5);
        } catch (IllegalArgumentException invalid) {
            return ToolInput.invalid(invalid.getMessage());
        }
        if (terms.isEmpty() || terms.size() > PassageSearch.MAX_TERMS) {
            return ToolInput.invalid("query must contain 1 to " + PassageSearch.MAX_TERMS
                    + " words of at least two letters or digits");
        }
        DocumentText text;
        try {
            text = texts.text(invocation.actor(), new DocumentId(id));
        } catch (ContentUnavailableException unavailable) {
            return Result.error(JsonText.write(Map.of("error", "document.textUnavailable",
                    "detail", "The archive cannot deliver the text right now.")));
        }
        return Result.ok(response(id, text, PassageSearch.find(text, terms, limit)));
    }

    static String response(UUID id, DocumentText text, PassageSearch.Result result) {
        String content = text.text();
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("id", id.toString());
        answer.put("title", text.title());
        answer.put("totalCharacters", content.length());
        if (content.isBlank()) {
            answer.put("textRecognized", false);
            answer.put("note", "The archive recognized no text in this document, for example a photo it could "
                    + "not read. Its content is unknown.");
            return JsonText.write(answer);
        }
        if (text.paged()) {
            answer.put("pages", text.pages().size());
        }
        answer.put("words", result.terms().stream().map(PassageSearch.Term::word).toList());
        answer.put("wordsNotFound", result.missingTerms());
        List<Map<String, Object>> passages = new ArrayList<>();
        for (Passage passage : result.passages()) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("offset", passage.start());
            view.put("end", passage.end());
            passage.page().ifPresent(page -> view.put("page", page));
            view.put("words", passage.terms());
            view.put("matches", passage.matches());
            view.put("text", UntrustedContent.wrap("document", id.toString(),
                    ReadDocumentTool.render(text, content, passage.start(), passage.end()), Integer.MAX_VALUE));
            passages.add(view);
        }
        int omitted = result.omitted();
        String json = write(answer, passages, omitted);
        while (json.length() > MAX_JSON && !passages.isEmpty()) {
            passages.removeLast();
            omitted++;
            json = write(answer, passages, omitted);
        }
        return json;
    }

    private static String write(Map<String, Object> answer, List<Map<String, Object>> passages, int omitted) {
        Map<String, Object> view = new LinkedHashMap<>(answer);
        view.put("passages", passages);
        view.put("omittedPassages", omitted);
        if (passages.isEmpty() && omitted == 0) {
            view.put("note", "None of the words occurs in the recognized text. The document may use other words, "
                    + "or OCR may have misread them; this does not prove that the document does not say it.");
        }
        return JsonText.write(view);
    }
}
