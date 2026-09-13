package dev.aegis.identity.domain;

import dev.aegis.shared.InvalidInputException;
import java.util.Locale;
import java.util.regex.Pattern;

/** Login name of a member: lowercase, 3 to 40 characters, letters, digits, dot, underscore or hyphen. */
public record Username(String value) {
    private static final Pattern VALID = Pattern.compile("[a-z0-9._-]{3,40}");

    public Username {
        if (value == null || !VALID.matcher(value).matches()) {
            throw new InvalidInputException("username.invalid", "Username must match " + VALID.pattern());
        }
    }

    /** Normalizes user input (trimming and lowercasing) before validation. */
    public static Username of(String raw) {
        return new Username(raw == null ? null : raw.trim().toLowerCase(Locale.ROOT));
    }

    @Override
    public String toString() {
        return value;
    }
}
