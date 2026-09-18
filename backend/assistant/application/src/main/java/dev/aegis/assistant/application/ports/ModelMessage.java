package dev.aegis.assistant.application.ports;

import java.util.List;
import java.util.Objects;

/**
 * One conversation turn. {@code providerEcho} is an opaque value a gateway attached to its own assistant turn (for
 * example thinking blocks that must be sent back unchanged); the application passes it through without inspecting it
 * and a gateway ignores echoes it did not create.
 */
public record ModelMessage(Role role, List<ContentPart> parts, Object providerEcho) {
    public enum Role {
        USER,
        ASSISTANT
    }

    public ModelMessage {
        Objects.requireNonNull(role, "role");
        parts = List.copyOf(parts);
    }

    public static ModelMessage user(String text) {
        return new ModelMessage(Role.USER, List.of(new ContentPart.Text(text)), null);
    }

    public static ModelMessage toolResults(List<ContentPart.ToolResult> results) {
        return new ModelMessage(Role.USER, List.copyOf(results), null);
    }

    public int characters() {
        int total = 0;
        for (ContentPart part : parts) {
            total += switch (part) {
                case ContentPart.Text text -> text.text().length();
                case ContentPart.ToolCall call -> call.name().length() + call.input().toString().length();
                case ContentPart.ToolResult result -> result.content().length();
            };
        }
        return total;
    }
}
