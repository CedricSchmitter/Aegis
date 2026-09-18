package dev.aegis.assistant.application.ports;

import java.util.Map;
import java.util.Objects;

/** Provider-neutral message content. Tool arguments and results are plain Java values (maps, lists, scalars). */
public sealed interface ContentPart permits ContentPart.Text, ContentPart.ToolCall, ContentPart.ToolResult {

    record Text(String text) implements ContentPart {
        public Text {
            Objects.requireNonNull(text, "text");
        }
    }

    /** A tool call requested by the model. Its name and input are untrusted until the executor checked them. */
    record ToolCall(String id, String name, Map<String, Object> input) implements ContentPart {
        public ToolCall {
            Objects.requireNonNull(id, "id");
            name = name == null ? "" : name;
            input = input == null ? Map.of() : input;
        }
    }

    record ToolResult(String callId, String content, boolean error) implements ContentPart {
        public ToolResult {
            Objects.requireNonNull(callId, "call id");
            Objects.requireNonNull(content, "content");
        }
    }
}
