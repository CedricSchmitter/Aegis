package dev.aegis.identity.application.api;

import dev.aegis.shared.MemberId;
import dev.aegis.shared.ProductLocale;
import java.util.Objects;

/**
 * Public read model of a member for other contexts and inbound adapters. Never carries credentials. The appearance is
 * the member's own presentation preference; it carries no authority.
 */
public record MemberSummary(MemberId id, String username, String displayName, ProductLocale locale, boolean active,
        MemberKind kind, MemberAppearance appearance) {
    public MemberSummary {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(appearance, "appearance");
    }

    /** A member with the default appearance. */
    public MemberSummary(MemberId id, String username, String displayName, ProductLocale locale, boolean active,
            MemberKind kind) {
        this(id, username, displayName, locale, active, kind, MemberAppearance.DEFAULT);
    }

    /** A person of the household, the default for members stored before member kinds existed. */
    public MemberSummary(MemberId id, String username, String displayName, ProductLocale locale, boolean active) {
        this(id, username, displayName, locale, active, MemberKind.PERSON);
    }

    public boolean isPerson() {
        return kind == MemberKind.PERSON;
    }
}
