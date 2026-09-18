package dev.aegis.assistant.application;

import dev.aegis.assistant.application.ports.ModelMessage;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Instructions and initial messages of a run. Untrusted material (document text, titles, mail) must be embedded with
 * {@link UntrustedContent#wrap}. {@code outputSchema} constrains the final answer.
 */
public record PreparedRun(String system, List<ModelMessage> messages, Map<String, Object> outputSchema) {
    public PreparedRun {
        Objects.requireNonNull(system, "system");
        messages = List.copyOf(messages);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("a run needs at least one message");
        }
    }
}
