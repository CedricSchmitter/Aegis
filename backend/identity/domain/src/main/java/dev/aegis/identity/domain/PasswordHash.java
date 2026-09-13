package dev.aegis.identity.domain;

import java.util.Objects;

/** Opaque password hash produced by a {@link PasswordHasher}. Never contains the plain password. */
public record PasswordHash(String value) {
    public PasswordHash {
        Objects.requireNonNull(value, "hash");
        if (value.isBlank()) {
            throw new IllegalArgumentException("password hash must not be blank");
        }
    }

    @Override
    public String toString() {
        return "PasswordHash[***]";
    }
}
