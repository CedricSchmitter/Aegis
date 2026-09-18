package dev.aegis.assistant.adapters.anthropic;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.anthropic.models.messages.Usage;
import dev.aegis.assistant.application.ports.ContentPart;
import dev.aegis.assistant.application.ports.ModelMessage;
import dev.aegis.assistant.application.ports.ModelRequest;
import dev.aegis.assistant.application.ports.ModelTurn;
import dev.aegis.assistant.application.ports.ToolSpec;
import dev.aegis.assistant.domain.TokenUsage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Translation between the provider-neutral ports and the Messages API types of the official SDK. Only the fields the
 * application asked for are sent: no sampling parameters, no server tools, no beta features.
 */
final class AnthropicMapping {
    private AnthropicMapping() {
    }

    static MessageCreateParams toParams(ModelRequest request) {
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(request.model())
                .maxTokens(request.maxOutputTokens())
                .system(request.system());
        for (ModelMessage message : request.messages()) {
            params.addMessage(toParam(message));
        }
        for (ToolSpec tool : request.tools()) {
            params.addTool(tool(tool));
        }
        OutputConfig.Builder output = null;
        if (request.outputSchema() != null) {
            output = OutputConfig.builder().format(JsonOutputFormat.builder()
                    .schema(JsonOutputFormat.Schema.builder().putAllAdditionalProperties(values(request.outputSchema()))
                            .build())
                    .build());
        }
        if (request.effort() != null) {
            output = output == null ? OutputConfig.builder() : output;
            output.effort(OutputConfig.Effort.of(request.effort()));
        }
        if (output != null) {
            params.outputConfig(output.build());
        }
        return params.build();
    }

    static MessageParam toParam(ModelMessage message) {
        if (message.role() == ModelMessage.Role.ASSISTANT && message.providerEcho() instanceof MessageParam echo) {
            // Sent back unchanged so that thinking blocks stay attached to their tool calls.
            return echo;
        }
        List<ContentBlockParam> blocks = new ArrayList<>();
        for (ContentPart part : message.parts()) {
            blocks.add(switch (part) {
                case ContentPart.Text text ->
                        ContentBlockParam.ofText(TextBlockParam.builder().text(text.text()).build());
                case ContentPart.ToolCall call -> ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                        .id(call.id()).name(call.name())
                        .input(ToolUseBlockParam.Input.builder().putAllAdditionalProperties(values(call.input()))
                                .build())
                        .build());
                case ContentPart.ToolResult result -> ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                        .toolUseId(result.callId()).content(result.content()).isError(result.error()).build());
            });
        }
        return MessageParam.builder()
                .role(message.role() == ModelMessage.Role.USER ? MessageParam.Role.USER : MessageParam.Role.ASSISTANT)
                .contentOfBlockParams(blocks).build();
    }

    static Tool tool(ToolSpec spec) {
        Map<String, Object> schema = spec.inputSchema();
        Tool.InputSchema.Builder input = Tool.InputSchema.builder();
        if (schema.get("properties") instanceof Map<?, ?> properties) {
            input.properties(Tool.InputSchema.Properties.builder().putAllAdditionalProperties(values(properties))
                    .build());
        }
        if (schema.get("required") instanceof List<?> required) {
            input.required(required.stream().map(String::valueOf).toList());
        }
        if (schema.containsKey("additionalProperties")) {
            input.putAdditionalProperty("additionalProperties", JsonValue.from(schema.get("additionalProperties")));
        }
        return Tool.builder().name(spec.name()).description(spec.description()).inputSchema(input.build()).build();
    }

    static ModelTurn toTurn(Message message, boolean structured, ObjectMapper json) {
        List<ContentPart> parts = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : message.content()) {
            block.text().ifPresent(value -> {
                parts.add(new ContentPart.Text(value.text()));
                text.append(value.text());
            });
            block.toolUse().ifPresent(call -> parts.add(new ContentPart.ToolCall(call.id(), call.name(),
                    objectOf(call._input()))));
        }
        ModelTurn.Stop stop = message.stopReason().map(AnthropicMapping::stop).orElse(ModelTurn.Stop.OTHER);
        Usage usage = message.usage();
        TokenUsage tokens = new TokenUsage(usage.inputTokens(), usage.outputTokens(),
                usage.cacheReadInputTokens().orElse(0L), usage.cacheCreationInputTokens().orElse(0L));
        Object structuredOutput = structured && stop == ModelTurn.Stop.END ? parse(text.toString(), json) : null;
        return new ModelTurn(parts, stop, tokens, message.model().asString(), text.toString(), structuredOutput,
                message.toParam());
    }

    static ModelTurn.Stop stop(StopReason reason) {
        return switch (reason.asString()) {
            case "end_turn", "stop_sequence" -> ModelTurn.Stop.END;
            case "tool_use" -> ModelTurn.Stop.TOOL_USE;
            case "max_tokens", "model_context_window_exceeded" -> ModelTurn.Stop.MAX_TOKENS;
            case "refusal" -> ModelTurn.Stop.REFUSAL;
            default -> ModelTurn.Stop.OTHER;
        };
    }

    private static Object parse(String text, ObjectMapper json) {
        try {
            return json.readValue(text.strip(), Object.class);
        } catch (JacksonException | IllegalArgumentException invalid) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectOf(JsonValue value) {
        try {
            Object converted = value.convert(Object.class);
            return converted instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        } catch (RuntimeException unconvertible) {
            return Map.of();
        }
    }

    private static Map<String, JsonValue> values(Map<?, ?> map) {
        Map<String, JsonValue> values = new LinkedHashMap<>();
        map.forEach((key, value) -> values.put(String.valueOf(key), JsonValue.from(value)));
        return values;
    }
}
