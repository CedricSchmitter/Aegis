package dev.aegis.identity.application.api;

import java.util.Objects;

/**
 * Public form of a member's appearance preference: the colour palette and whether the effects layer is on (ADR-0019).
 * Values are stable English enum names; the web app maps them to its own presentation.
 */
public record MemberAppearance(Palette palette, boolean effects) {
    /** Streetkid with effects on. */
    public static final MemberAppearance DEFAULT = new MemberAppearance(Palette.STREETKID, true);

    public MemberAppearance {
        Objects.requireNonNull(palette, "palette");
    }

    public enum Palette {
        STREETKID,
        CORPO,
        NOMAD
    }
}
