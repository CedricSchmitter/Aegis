package dev.aegis.identity.application.api;

import dev.aegis.shared.MemberId;
import dev.aegis.shared.ProductLocale;

/** Public read model of a member for other contexts and inbound adapters. Never carries credentials. */
public record MemberSummary(MemberId id, String username, String displayName, ProductLocale locale, boolean active) {
}
