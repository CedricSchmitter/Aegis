package dev.aegis.assistant.adapters.tools;

import dev.aegis.assistant.application.JsonText;
import dev.aegis.assistant.application.chat.ChatTools;
import dev.aegis.assistant.application.ports.AiTool;
import dev.aegis.assistant.application.ports.ToolSpec;
import dev.aegis.documents.application.api.CatalogQueries;
import dev.aegis.documents.application.api.CategoryListing;
import dev.aegis.documents.application.api.CategoryView;
import dev.aegis.documents.application.api.LabelView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The household's folder tree and active labels, so the model can narrow {@code search_documents} with ids. Built-in
 * labels without a member-chosen name are listed by their stable key.
 */
@Component
public class HouseholdCatalogTool implements AiTool {
    static final int MAX_FOLDERS = 300;
    static final int MAX_LABELS = 400;

    private static final ToolSpec SPEC = new ToolSpec(ChatTools.HOUSEHOLD_CATALOG,
            "Lists the household's folders (id, path, number of documents filed directly in it) and active labels "
                    + "by group: DOCUMENT_TYPE, ORGANIZATION, TOPIC and TAG (id, name, aliases, number of documents). "
                    + "Use the ids with search_documents.",
            Map.of("type", "object", "properties", Map.of(), "additionalProperties", false));

    private final CatalogQueries catalog;

    public HouseholdCatalogTool(CatalogQueries catalog) {
        this.catalog = catalog;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public Result invoke(Invocation invocation, Map<String, Object> input) {
        CategoryListing listing = catalog.categories(invocation.actor());
        Map<UUID, CategoryView> byId = new HashMap<>();
        listing.categories().forEach(category -> byId.put(category.id(), category));
        List<Map<String, Object>> folders = listing.categories().stream()
                .map(category -> Map.entry(path(category, byId), category))
                .sorted(Comparator.comparing(entry -> String.join("/", entry.getKey())))
                .map(entry -> {
                    Map<String, Object> folder = new LinkedHashMap<>();
                    folder.put("id", entry.getValue().id().toString());
                    folder.put("path", entry.getKey());
                    folder.put("documents", entry.getValue().documentCount());
                    return folder;
                })
                .toList();
        Map<String, List<Map<String, Object>>> labels = new LinkedHashMap<>();
        catalog.labels(invocation.actor()).stream()
                .filter(label -> "ACTIVE".equals(label.status()) && label.mergedInto() == null)
                .sorted(Comparator.comparing(LabelView::facet).thenComparing(HouseholdCatalogTool::name))
                .limit(MAX_LABELS)
                .forEach(label -> labels.computeIfAbsent(label.facet(), facet -> new ArrayList<>()).add(entry(label)));
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("totalDocuments", listing.totalDocuments());
        answer.put("unsortedDocuments", listing.unsortedCount());
        answer.put("folders", folders.size() <= MAX_FOLDERS ? folders : folders.subList(0, MAX_FOLDERS));
        answer.put("foldersOmitted", Math.max(0, folders.size() - MAX_FOLDERS));
        answer.put("labels", labels);
        return Result.ok(JsonText.write(answer));
    }

    private static List<String> path(CategoryView category, Map<UUID, CategoryView> byId) {
        List<String> path = new ArrayList<>();
        CategoryView current = category;
        while (current != null && path.size() < 20) {
            path.addFirst(current.name());
            current = current.parentId() == null ? null : byId.get(current.parentId());
        }
        return path;
    }

    private static Map<String, Object> entry(LabelView label) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", label.id().toString());
        entry.put("name", name(label));
        if (!label.aliases().isEmpty()) {
            entry.put("aliases", label.aliases());
        }
        entry.put("documents", label.assignmentCount());
        return entry;
    }

    private static String name(LabelView label) {
        return label.name() != null ? label.name() : String.valueOf(label.builtinKey());
    }
}
