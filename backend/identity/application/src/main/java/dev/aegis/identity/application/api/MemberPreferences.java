package dev.aegis.identity.application.api;

import dev.aegis.shared.Actor;
import dev.aegis.shared.ProductLocale;

/** Self-service preferences of the authenticated member. */
public interface MemberPreferences {
    MemberSummary currentMember(Actor actor);

    MemberSummary changeLocale(Actor actor, ProductLocale locale);

    /** Stores palette and effects for the authenticated member only; other members keep their own. */
    MemberSummary changeAppearance(Actor actor, MemberAppearance appearance);

    void changePassword(Actor actor, String currentPassword, String newPassword);
}
