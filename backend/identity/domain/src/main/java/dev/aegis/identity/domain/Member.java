package dev.aegis.identity.domain;

import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.ProductLocale;
import java.time.Instant;
import java.util.Objects;

/**
 * A household member with an individual login. Membership in the single shared household is implicit for this
 * release; the aggregate protects credential and preference invariants.
 */
public final class Member {
    public static final int MIN_PASSWORD_LENGTH = 10;

    private final MemberId id;
    private final Username username;
    private String displayName;
    private PasswordHash passwordHash;
    private ProductLocale locale;
    private boolean active;
    private final Instant createdAt;

    private Member(MemberId id, Username username, String displayName, PasswordHash passwordHash, ProductLocale locale,
            boolean active, Instant createdAt) {
        this.id = Objects.requireNonNull(id);
        this.username = Objects.requireNonNull(username);
        this.displayName = requireDisplayName(displayName);
        this.passwordHash = Objects.requireNonNull(passwordHash);
        this.locale = Objects.requireNonNull(locale);
        this.active = active;
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public static Member register(Username username, String displayName, String plainPassword, PasswordHasher hasher,
            ProductLocale locale, Instant now) {
        requirePasswordPolicy(plainPassword);
        return new Member(MemberId.random(), username, displayName, hasher.hash(plainPassword), locale, true, now);
    }

    /** Rehydrates a persisted member without re-validating credentials. */
    public static Member rehydrate(MemberId id, Username username, String displayName, PasswordHash passwordHash,
            ProductLocale locale, boolean active, Instant createdAt) {
        return new Member(id, username, displayName, passwordHash, locale, active, createdAt);
    }

    public boolean passwordMatches(String plainPassword, PasswordHasher hasher) {
        return active && hasher.matches(plainPassword, passwordHash);
    }

    public void changePassword(String currentPassword, String newPassword, PasswordHasher hasher) {
        if (!hasher.matches(currentPassword, passwordHash)) {
            throw new InvalidInputException("password.current.mismatch", "Current password does not match");
        }
        requirePasswordPolicy(newPassword);
        this.passwordHash = hasher.hash(newPassword);
    }

    public void changeLocale(ProductLocale newLocale) {
        this.locale = Objects.requireNonNull(newLocale);
    }

    public void rename(String newDisplayName) {
        this.displayName = requireDisplayName(newDisplayName);
    }

    public void deactivate() {
        this.active = false;
    }

    public MemberId id() {
        return id;
    }

    public Username username() {
        return username;
    }

    public String displayName() {
        return displayName;
    }

    public PasswordHash passwordHash() {
        return passwordHash;
    }

    public ProductLocale locale() {
        return locale;
    }

    public boolean isActive() {
        return active;
    }

    public Instant createdAt() {
        return createdAt;
    }

    private static String requireDisplayName(String value) {
        if (value == null || value.isBlank() || value.length() > 80) {
            throw new InvalidInputException("displayName.invalid", "Display name must be 1 to 80 characters");
        }
        return value.trim();
    }

    private static void requirePasswordPolicy(String plainPassword) {
        if (plainPassword == null || plainPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new InvalidInputException("password.tooShort",
                    "Password must have at least " + MIN_PASSWORD_LENGTH + " characters");
        }
    }
}
