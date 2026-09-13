package dev.aegis.identity.domain;

/** Domain service contract for hashing and verifying passwords; implemented by a security adapter. */
public interface PasswordHasher {
    PasswordHash hash(String plainPassword);

    boolean matches(String plainPassword, PasswordHash hash);
}
