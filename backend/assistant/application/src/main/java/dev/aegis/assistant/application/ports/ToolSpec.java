package dev.aegis.assistant.application.ports;

import java.util.Map;
import java.util.Objects;

/** Declaration of a tool offered to the model; {@code inputSchema} is a JSON Schema object as plain Java values. */
public record ToolSpec(String name, String description, Map<String, Object> inputSchema) {
    public ToolSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        inputSchema = Map.copyOf(inputSchema);
        if (!name.matches("[a-z][a-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("tool names are lower-case identifiers: " + name);
        }
    }
}
