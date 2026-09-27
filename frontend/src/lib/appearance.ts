import type { AppearanceDto } from "../api/types";

/** Colour palettes of the design system (ADR-0019); they switch only the action and data colours. */
export const PALETTES = ["streetkid", "corpo", "nomad"] as const;
export type Palette = (typeof PALETTES)[number];
export const DEFAULT_PALETTE: Palette = "streetkid";

/** The action and data colour of each palette, for the samples in Settings; styles.css applies them. */
export const PALETTE_SWATCHES: Record<Palette, readonly [string, string]> = {
  streetkid: ["#fcee0a", "#00f0ff"],
  corpo: ["#dce6f5", "#5aa9ff"],
  nomad: ["#ff9a3c", "#4fe3c1"],
};

export type Appearance = { palette: Palette; effects: boolean };

/** Streetkid with effects on: before login, after logout and for every member who never chose otherwise. */
export const DEFAULT_APPEARANCE: Appearance = { palette: DEFAULT_PALETTE, effects: true };

/** Reads the appearance from the root attributes; missing or unknown values mean Streetkid with effects on. */
export function readAppearance(root: HTMLElement): Appearance {
  const palette = PALETTES.find((candidate) => candidate === root.dataset.palette) ?? DEFAULT_PALETTE;
  return { palette, effects: root.dataset.effects !== "off" };
}

/**
 * Applies the appearance through root attributes, which styles.css reads. The member's stored preference arrives
 * with the session; reduced motion in the browser still stops every animation whatever `effects` says.
 */
export function applyAppearance(root: HTMLElement, appearance: Appearance) {
  root.dataset.palette = appearance.palette;
  root.dataset.effects = appearance.effects ? "on" : "off";
}

/** The API sends stable enum values ("NOMAD"); an unknown value falls back to the default palette. */
export function fromApi(dto: AppearanceDto | null | undefined): Appearance {
  if (!dto) return DEFAULT_APPEARANCE;
  const palette = PALETTES.find((candidate) => candidate.toUpperCase() === dto.palette) ?? DEFAULT_PALETTE;
  return { palette, effects: dto.effects };
}

export function toApi(appearance: Appearance): AppearanceDto {
  return { palette: appearance.palette.toUpperCase() as AppearanceDto["palette"], effects: appearance.effects };
}
