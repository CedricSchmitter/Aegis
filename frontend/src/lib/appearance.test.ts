// @vitest-environment jsdom
import { describe, expect, it } from "vitest";
import { applyAppearance, readAppearance } from "./appearance";

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
});
