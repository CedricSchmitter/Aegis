package dev.aegis.assistant.adapters.tools;

import dev.aegis.assistant.application.JsonText;
import dev.aegis.assistant.application.ports.AiTool.Result;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Checks of the model's tool input, which is untrusted: wrong types, unknown ids and out-of-range numbers are
 * reported back to the model as {@code input.invalid} instead of reaching the application.
 */
final class ToolInput {
    private ToolInput() {
    }

    static Result invalid(String detail) {
        return Result.error(JsonText.write(Map.of("error", "input.invalid", "detail", detail)));
    }

    static String text(Map<String, Object> input, String name, int maxLength, boolean required) {
        Object value = input.get(name);
        if (value == null && !required) {
            return null;
        }
        if (!(value instanceof String text) || text.length() > maxLength) {
            throw new IllegalArgumentException(name + " must be a text of at most " + maxLength + " characters");
        }
        return text;
    }

    static int integer(Map<String, Object> input, String name, int min, int max, int fallback) {
        Object value = input.get(name);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(name + " must be a number");
        }
        return Math.max(min, Math.min(max, number.intValue()));
    }

    static boolean flag(Map<String, Object> input, String name, boolean fallback) {
        Object value = input.get(name);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Boolean flag)) {
            throw new IllegalArgumentException(name + " must be true or false");
        }
        return flag;
    }

    static UUID uuid(Map<String, Object> input, String name) {
        Object value = input.get(name);
        if (value == null || value instanceof String text && text.isBlank()) {
            return null;
        }
        return parse(name, value);
    }

    static UUID requiredUuid(Map<String, Object> input, String name) {
        UUID id = uuid(input, name);
        if (id == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return id;
    }

    static List<UUID> uuids(Map<String, Object> input, String name, int max) {
        Object value = input.get(name);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> items) || items.size() > max) {
            throw new IllegalArgumentException(name + " must be a list of at most " + max + " ids");
        }
        List<UUID> ids = new ArrayList<>();
        for (Object item : items) {
            ids.add(parse(name, item));
        }
        return ids;
    }

    private static UUID parse(String name, Object value) {
        try {
            return UUID.fromString(String.valueOf(value).strip());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(name + " must contain ids returned by a tool");
        }
    }
}
