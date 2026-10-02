package dev.aegis.assistant.adapters.tools;

import dev.aegis.assistant.application.JsonText;
import dev.aegis.assistant.application.chat.ChatTools;
import dev.aegis.assistant.application.ports.AiTool;
import dev.aegis.assistant.application.ports.ToolSpec;
import dev.aegis.identity.application.api.MemberDirectory;
import dev.aegis.identity.application.api.MemberSummary;
import dev.aegis.planning.application.api.TaskQueries;
import dev.aegis.planning.application.api.TaskSummary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The household's tasks through the planning context's query contract. The due date is the actual (contractual)
 * deadline; the planned date is when someone intends to do the work. They are kept apart, as everywhere in Aegis.
 */
@Component
public class ListTasksTool implements AiTool {
    static final int MAX_TASKS = 100;
    static final int MAX_NOTE = 300;

    private static final ToolSpec SPEC = new ToolSpec(ChatTools.LIST_TASKS,
            "Lists the household's tasks (not in the trash): id, title, status, owner, due date (the actual "
                    + "deadline), planned work day, a short note, the source document and, for cancellation reviews, "
                    + "the contract and its cancellation deadline. Open tasks only unless includeDone is true.",
            Map.of("type", "object",
                    "properties", Map.of(
                            "query", Map.of("type", "string",
                                    "description", "Optional words that the title or note must contain"),
                            "includeDone", Map.of("type", "boolean", "description", "Include completed tasks")),
                    "additionalProperties", false));

    private final TaskQueries tasks;
    private final MemberDirectory members;

    public ListTasksTool(TaskQueries tasks, MemberDirectory members) {
        this.tasks = tasks;
        this.members = members;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public Result invoke(Invocation invocation, Map<String, Object> input) {
        String query;
        boolean includeDone;
        try {
            query = ToolInput.text(input, "query", 200, false);
            includeDone = ToolInput.flag(input, "includeDone", false);
        } catch (IllegalArgumentException invalid) {
            return ToolInput.invalid(invalid.getMessage());
        }
        List<String> words = query == null ? List.of()
                : List.of(query.toLowerCase(Locale.ROOT).split("\\s+")).stream().filter(word -> !word.isBlank())
                        .toList();
        List<Map<String, Object>> items = new ArrayList<>();
        int matching = 0;
        for (TaskSummary task : tasks.list(invocation.actor(), includeDone)) {
            String haystack = (task.title() + " " + (task.note() == null ? "" : task.note()))
                    .toLowerCase(Locale.ROOT);
            if (!words.stream().allMatch(haystack::contains)) {
                continue;
            }
            matching++;
            if (items.size() < MAX_TASKS) {
                items.add(item(task));
            }
        }
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("matchingTasks", matching);
        answer.put("tasks", items);
        return Result.ok(JsonText.write(answer));
    }

    private Map<String, Object> item(TaskSummary task) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", task.id().toString());
        item.put("title", task.title());
        item.put("status", task.status());
        item.put("owner", task.ownerId() == null ? null
                : members.findMember(task.ownerId()).map(MemberSummary::displayName).orElse(null));
        item.put("dueDate", ContractJson.text(task.dueDate()));
        item.put("plannedDate", ContractJson.text(task.plannedDate()));
        if (task.note() != null && !task.note().isBlank()) {
            item.put("note", task.note().length() <= MAX_NOTE ? task.note()
                    : task.note().substring(0, MAX_NOTE) + "…");
        }
        if (task.source() != null) {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("documentId", task.source().documentId().toString());
            source.put("documentTitle", task.source().documentTitle());
            item.put("source", source);
        }
        if (task.cancellationReview() != null) {
            Map<String, Object> review = new LinkedHashMap<>();
            review.put("contractId", task.cancellationReview().contractId().toString());
            review.put("contractName", task.cancellationReview().contractName());
            review.put("cancellationDeadline", ContractJson.text(task.cancellationReview().cancellationDeadline()));
            item.put("cancellationReview", review);
        }
        if (task.completedAt() != null) {
            item.put("completedAt", task.completedAt().toString());
        }
        return item;
    }
}
