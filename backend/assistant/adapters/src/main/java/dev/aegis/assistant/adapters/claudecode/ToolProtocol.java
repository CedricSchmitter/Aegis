package dev.aegis.assistant.adapters.claudecode;

import dev.aegis.assistant.application.JsonText;
import dev.aegis.assistant.application.ports.ContentPart;
import dev.aegis.assistant.application.ports.ModelCallException;
import dev.aegis.assistant.application.ports.ModelMessage;
import dev.aegis.assistant.application.ports.ModelRequest;
import dev.aegis.assistant.application.ports.ModelTurn;
import dev.aegis.assistant.application.ports.ToolSpec;
import dev.aegis.assistant.domain.FailureCategory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool calls and multi-turn conversations over the Claude Code CLI, which runs here without tools and without stored
 * sessions (ADR-0003, amended for issue #25). Every model call is one stateless {@code claude -p} run: the
 * conversation so far, including earlier tool calls and their results, is sent as JSON, and the answer's JSON schema
 * lets the model either request tool calls or give its final answer. Aegis runs the requested tools itself with the
 * same grants, limits and audit as for the API provider; the CLI never executes anything.
 *
 * <p>The conversation is JSON rather than tagged text, so content inside a tool result or an earlier answer cannot
 * close its frame and pose as another role.
 */
final class ToolProtocol {
    static final int MAX_CALLS_PER_TURN = 4;
    static final String TOOL_CALLS = "toolCalls";
    static final String ANSWER = "answer";

    private ToolProtocol() {
    }

    /** Whether the request is one user prompt without tools, which the CLI takes as it is. */
    static boolean singlePrompt(ModelRequest request) {
        return request.tools().isEmpty() && request.messages().stream()
                .allMatch(message -> message.role() == ModelMessage.Role.USER
                        && message.parts().stream().allMatch(ContentPart.Text.class::isInstance));
    }

    static String system(ModelRequest request) {
        StringBuilder system = new StringBuilder(request.system()).append("\n\n").append("""
                How this conversation works: you receive the conversation so far as a JSON array, oldest entry \
                first. Each entry is a member's message ("user"), one of your earlier answers ("assistant"), your \
                earlier tool calls ("tool_calls") or their results ("tool_results"). Reply only with the JSON object \
                of the required schema.""");
        if (!request.tools().isEmpty()) {
            system.append("\n\n").append("""
                    You cannot run tools yourself, and your own environment offers none: the tools below are never \
                    available as functions you call directly, only through toolCalls, which always works. To use \
                    tools, put up to %d calls into toolCalls, each with the tool's name and an input object that \
                    matches its input schema, and set answer to null. The application runs them and asks you again \
                    with their results appended. When you are done, set \
                    toolCalls to an empty list and give your final answer in answer.

                    Tools:""".formatted(MAX_CALLS_PER_TURN));
            for (ToolSpec tool : request.tools()) {
                system.append("\n- ").append(tool.name()).append(": ").append(tool.description())
                        .append("\n  Input schema: ").append(JsonText.write(tool.inputSchema()));
            }
        } else {
            system.append(" Give your answer in answer.");
        }
        return system.toString();
    }

    static String prompt(List<ModelMessage> messages) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (ModelMessage message : messages) {
            List<String> texts = new ArrayList<>();
            List<Map<String, Object>> calls = new ArrayList<>();
            List<Map<String, Object>> results = new ArrayList<>();
            for (ContentPart part : message.parts()) {
                switch (part) {
                    case ContentPart.Text text -> texts.add(text.text());
                    case ContentPart.ToolCall call -> calls.add(Map.of("id", call.id(), "name", call.name(),
                            "input", call.input()));
                    case ContentPart.ToolResult result -> results.add(Map.of("callId", result.callId(),
                            "error", result.error(), "content", result.content()));
                }
            }
            String role = message.role() == ModelMessage.Role.USER ? "user" : "assistant";
            if (!texts.isEmpty()) {
                entries.add(Map.of("role", role, "text", String.join("\n\n", texts)));
            }
            if (!calls.isEmpty()) {
                entries.add(Map.of("role", "tool_calls", "calls", calls));
            }
            if (!results.isEmpty()) {
                entries.add(Map.of("role", "tool_results", "results", results));
            }
        }
        return "The conversation so far:\n" + JsonText.write(entries)
                + "\n\nContinue: request tool calls or give the final answer to the latest user message.";
    }

    /** The answer schema: tool calls (when tools are offered) or the workload's answer, one of them empty. */
    static Map<String, Object> schema(ModelRequest request) {
        Map<String, Object> answer;
        if (request.outputSchema() == null) {
            answer = Map.of("type", List.of("string", "null"));
        } else {
            answer = new LinkedHashMap<>(request.outputSchema());
            answer.put("type", List.of("object", "null"));
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        if (!request.tools().isEmpty()) {
            properties.put(TOOL_CALLS, Map.of("type", "array", "items", Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "name", Map.of("type", "string",
                                    "enum", request.tools().stream().map(ToolSpec::name).toList()),
                            "input", Map.of("type", "object")),
                    "required", List.of("name", "input"),
                    "additionalProperties", false)));
        }
        properties.put(ANSWER, answer);
        return Map.of("type", "object", "properties", properties, "required", List.copyOf(properties.keySet()),
                "additionalProperties", false);
    }

    /**
     * Turns the CLI's structured answer into tool calls or the final answer. Call ids continue the numbering of the
     * conversation so they stay unique within a run.
     */
    static ModelTurn turn(ModelTurn envelope, ModelRequest request) {
        if (envelope.stop() != ModelTurn.Stop.END) {
            return envelope;
        }
        if (!(envelope.structuredOutput() instanceof Map<?, ?> reply)) {
            throw invalid("the CLI answer is not the required JSON object");
        }
        List<ContentPart> calls = new ArrayList<>();
        if (!request.tools().isEmpty() && reply.get(TOOL_CALLS) instanceof List<?> requested) {
            int next = previousCalls(request.messages());
            for (Object item : requested) {
                if (!(item instanceof Map<?, ?> call) || !(call.get("name") instanceof String name)) {
                    throw invalid("a tool call needs a name");
                }
                Map<String, Object> input = new LinkedHashMap<>();
                if (call.get("input") instanceof Map<?, ?> values) {
                    values.forEach((key, value) -> input.put(String.valueOf(key), value));
                }
                calls.add(new ContentPart.ToolCall("call_" + (++next), name, input));
            }
        }
        if (!calls.isEmpty()) {
            return new ModelTurn(calls, ModelTurn.Stop.TOOL_USE, envelope.usage(), envelope.modelId(), "", null,
                    null);
        }
        Object answer = reply.get(ANSWER);
        if (answer == null) {
            throw invalid("the CLI answer has neither tool calls nor a final answer");
        }
        String text = answer instanceof String plain ? plain : JsonText.write(answer);
        return new ModelTurn(List.of(new ContentPart.Text(text)), ModelTurn.Stop.END, envelope.usage(),
                envelope.modelId(), text, request.outputSchema() == null ? null : answer, null);
    }

    private static int previousCalls(List<ModelMessage> messages) {
        return (int) messages.stream().flatMap(message -> message.parts().stream())
                .filter(ContentPart.ToolCall.class::isInstance).count();
    }

    private static ModelCallException invalid(String message) {
        return new ModelCallException(FailureCategory.INVALID_OUTPUT, "output.schemaMismatch", message);
    }
}
