// @vitest-environment jsdom
import { describe, expect, it } from "vitest";
import { applyAppearance, DEFAULT_APPEARANCE, fromApi, readAppearance, toApi } from "./appearance";

describe("appearance", () => {
  it("defaults to Streetkid with effects when the root carries no or unknown attributes", () => {
    const root = document.createElement("html");
    expect(readAppearance(root)).toEqual({ palette: "streetkid", effects: true });
    root.dataset.palette = "neon";
    root.dataset.effects = "maybe";
    expect(readAppearance(root)).toEqual({ palette: "streetkid", effects: true });
  });

  it("round-trips a palette and switched-off effects through the root attributes", () => {
    const root = document.createElement("html");
    applyAppearance(root, { palette: "nomad", effects: false });
    expect(root.getAttribute("data-palette")).toBe("nomad");
    expect(root.getAttribute("data-effects")).toBe("off");
    expect(readAppearance(root)).toEqual({ palette: "nomad", effects: false });
  });

  it("maps the stored member preference from and to the API's enum values", () => {
    expect(fromApi({ palette: "CORPO", effects: false })).toEqual({ palette: "corpo", effects: false });
    expect(toApi({ palette: "nomad", effects: true })).toEqual({ palette: "NOMAD", effects: true });
    for (const palette of ["STREETKID", "CORPO", "NOMAD"] as const) {
      expect(toApi(fromApi({ palette, effects: true })).palette).toBe(palette);
    }
  });

  it("falls back to the default without a stored preference or with a palette it does not know", () => {
    expect(fromApi(undefined)).toEqual(DEFAULT_APPEARANCE);
    expect(fromApi({ palette: "NEON" as never, effects: false })).toEqual({ palette: "streetkid", effects: false });
  });
});
