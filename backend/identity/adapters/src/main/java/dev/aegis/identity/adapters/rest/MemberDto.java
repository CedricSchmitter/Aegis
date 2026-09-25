package dev.aegis.identity.adapters.rest;

import dev.aegis.identity.application.api.MemberKind;
import dev.aegis.identity.application.api.MemberSummary;
import dev.aegis.shared.ProductLocale;

/**
 * REST representation of a member. Locale and kind use their stable enum values; only a {@code PERSON} can be what a
 * document is about, while every member can be responsible for a task.
 */
public record MemberDto(String id, String username, String displayName, ProductLocale locale, MemberKind kind) {
    static MemberDto from(MemberSummary summary) {
        return new MemberDto(summary.id().toString(), summary.username(), summary.displayName(), summary.locale(),
                summary.kind());
    }
}
