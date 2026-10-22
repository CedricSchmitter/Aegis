package dev.aegis.shared;

import java.util.Objects;

/**
 * One field of a recorded change (ADR-0030): a stable field code and its value before and after, each in a
 * locale-independent form (ISO dates, ids, enum names) or the member's own text. {@code null} means "no value".
 */
public record FieldChange(String field, String before, String after) {

    public FieldChange {
        Objects.requireNonNull(field, "field");
        if (Objects.equals(before, after)) {
            throw new IllegalArgumentException("a field change needs different values: " + field);
        }
    }
}
