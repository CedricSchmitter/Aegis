package dev.aegis.documents.adapters.rest;

import dev.aegis.shared.InvalidInputException;
import java.util.List;
import java.util.UUID;

/** Parsing of id strings at the HTTP boundary with the stable validation error code. */
final class RestIds {
    private RestIds() {
    }

    static UUID optional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException invalid) {
            throw new InvalidInputException("validation.failed", "Invalid id value");
        }
    }

    static UUID required(String value) {
        UUID id = optional(value);
        if (id == null) {
            throw new InvalidInputException("validation.failed", "Id is required");
        }
        return id;
    }

    static List<UUID> all(List<String> values) {
        return values == null ? List.of() : values.stream().map(RestIds::required).toList();
    }
}
