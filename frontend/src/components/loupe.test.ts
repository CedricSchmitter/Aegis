import { describe, expect, it } from "vitest";
import { loupePlacement } from "./loupe";

const phone = { width: 390, height: 844 };
const size = 128;

function covers(placement: { x: number; y: number }, point: { x: number; y: number }): boolean {
  return (
    point.x >= placement.x && point.x <= placement.x + size && point.y >= placement.y && point.y <= placement.y + size
  );
}

describe("loupePlacement", () => {
  it("places the magnifier above the finger when there is room", () => {
    const point = { x: 200, y: 600 };
    const placement = loupePlacement(point, phone, size);

    expect(placement.y + size).toBeLessThan(point.y);
    expect(placement.x + size / 2).toBe(point.x);
  });

  it("keeps the magnifier on screen for a corner at the side", () => {
    const placement = loupePlacement({ x: 4, y: 700 }, phone, size);

    expect(placement.x).toBeGreaterThanOrEqual(0);
    expect(placement.x + size).toBeLessThanOrEqual(phone.width);
  });

  it("moves beside the point towards the middle near the top of the screen", () => {
    const left = { x: 30, y: 110 };
    const right = { x: 360, y: 110 };

    const forLeft = loupePlacement(left, phone, size);
    const forRight = loupePlacement(right, phone, size);

    expect(forLeft.x).toBeGreaterThan(left.x);
    expect(forRight.x + size).toBeLessThan(right.x);
    for (const [placement, point] of [
      [forLeft, left],
      [forRight, right],
    ] as const) {
      expect(covers(placement, point)).toBe(false);
      expect(placement.y).toBeGreaterThanOrEqual(0);
    }
  });
});
