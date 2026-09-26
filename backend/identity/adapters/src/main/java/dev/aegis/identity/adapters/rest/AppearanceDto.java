package dev.aegis.identity.adapters.rest;

import dev.aegis.identity.application.api.MemberAppearance;
import dev.aegis.identity.application.api.MemberAppearance.Palette;
import jakarta.validation.constraints.NotNull;

/**
 * REST representation of a member's palette and effects preference (ADR-0019). The palette uses its stable enum value;
 * the web app maps it to its presentation. Also the request body of {@code PUT /api/members/me/appearance}.
 */
public record AppearanceDto(@NotNull Palette palette, @NotNull Boolean effects) {
    static AppearanceDto from(MemberAppearance appearance) {
        return new AppearanceDto(appearance.palette(), appearance.effects());
    }

    MemberAppearance toApplication() {
        return new MemberAppearance(palette, effects);
    }
}
