/** Colour palettes of the design system (ADR-0019); they switch only the action and data colours. */
export const PALETTES = ["streetkid", "corpo", "nomad"] as const;
export type Palette = (typeof PALETTES)[number];
export const DEFAULT_PALETTE: Palette = "streetkid";

export type Appearance = { palette: Palette; effects: boolean };

/** Reads the appearance from the root attributes; missing or unknown values mean Streetkid with effects on. */
export function readAppearance(root: HTMLElement): Appearance {
  const palette = PALETTES.find((candidate) => candidate === root.dataset.palette) ?? DEFAULT_PALETTE;
  return { palette, effects: root.dataset.effects !== "off" };
}

/** Applies the appearance through root attributes, which styles.css reads. It is not persisted here. */
export function applyAppearance(root: HTMLElement, appearance: Appearance) {
  root.dataset.palette = appearance.palette;
  root.dataset.effects = appearance.effects ? "on" : "off";
}
