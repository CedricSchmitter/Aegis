package dev.aegis.assistant.application.ports;

import dev.aegis.assistant.domain.TokenUsage;
import java.util.List;
import java.util.Objects;

/**
 * Result of one model call. {@code text} joins the text parts. {@code structuredOutput} is the parsed JSON value of
 * the text when the request carried an output schema and the text was valid JSON, otherwise null; it is still
 * untrusted and validated by the workload handler.
 */
public record ModelTurn(List<ContentPart> parts, Stop stop, TokenUsage usage, String modelId, String text,
        Object structuredOutput, Object providerEcho) {

    /** Why the model stopped. */
    public enum Stop {
        END,
        TOOL_USE,
        MAX_TOKENS,
        REFUSAL,
        OTHER
    }

    public ModelTurn {
        parts = List.copyOf(parts);
        Objects.requireNonNull(stop, "stop");
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(modelId, "model id");
        text = text == null ? "" : text;
    }

    public List<ContentPart.ToolCall> toolCalls() {
        return parts.stream().filter(ContentPart.ToolCall.class::isInstance).map(ContentPart.ToolCall.class::cast)
                .toList();
    }

    /** The turn as assistant message for the next call, carrying the provider's opaque echo. */
    public ModelMessage asAssistantMessage() {
        return new ModelMessage(ModelMessage.Role.ASSISTANT, parts, providerEcho);
    }
}
