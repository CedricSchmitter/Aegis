package dev.aegis.assistant.application.ports;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One model call. {@code tools} contains only the tools the workload grants. {@code outputSchema}, when present,
 * asks the provider for a JSON answer constrained to this schema. {@code timeout} is the remaining time of the
 * attempt, capped by the configured call timeout. {@code effort} is optional and only sent when configured.
 */
public record ModelRequest(String model, String system, List<ModelMessage> messages, List<ToolSpec> tools,
        Map<String, Object> outputSchema, int maxOutputTokens, Duration timeout, String effort) {
    public ModelRequest {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(system, "system");
        Objects.requireNonNull(timeout, "timeout");
        messages = List.copyOf(messages);
        tools = List.copyOf(tools == null ? List.of() : tools);
        if (maxOutputTokens < 1) {
            throw new IllegalArgumentException("max output tokens must be positive");
        }
    }
}
