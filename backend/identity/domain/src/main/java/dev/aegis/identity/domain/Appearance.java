package dev.aegis.identity.domain;

import java.util.Objects;

/**
 * How the web app looks for one member: the colour palette and whether the effects layer (grid, scanlines, wordmark
 * glitch) is on. A presentation preference like the locale; it never changes what a member can see or do, and the
 * browser's reduced-motion setting still stops every animation whatever {@code effects} says (ADR-0019).
 */
public record Appearance(Palette palette, boolean effects) {
    /** Streetkid with effects on, for every member who never chose otherwise. */
    public static final Appearance DEFAULT = new Appearance(Palette.STREETKID, true);

    public Appearance {
        Objects.requireNonNull(palette, "palette");
    }
}
