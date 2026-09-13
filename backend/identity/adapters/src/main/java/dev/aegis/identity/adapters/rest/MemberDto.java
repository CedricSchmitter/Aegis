package dev.aegis.identity.adapters.rest;

import dev.aegis.identity.application.api.MemberSummary;
import dev.aegis.shared.ProductLocale;

/** REST representation of a member. Locale uses the stable enum value. */
public record MemberDto(String id, String username, String displayName, ProductLocale locale) {
    static MemberDto from(MemberSummary summary) {
        return new MemberDto(summary.id().toString(), summary.username(), summary.displayName(), summary.locale());
    }
}
