import { describe, expect, it } from "vitest";
import {
  MAX_PAGE_PIXELS,
  interiorAngles,
  isConvex,
  isPlausibleSheet,
  lineIntersection,
  orderCorners,
  rectifiedSize,
  type Quad,
} from "./geometry";

const sheet: Quad = [
  { x: 0.2, y: 0.1 },
  { x: 0.8, y: 0.12 },
  { x: 0.82, y: 0.9 },
  { x: 0.18, y: 0.88 },
];

describe("sheet geometry", () => {
  it("orders corners from top-left clockwise whatever order they were found in", () => {
    expect(orderCorners([sheet[2], sheet[0], sheet[3], sheet[1]])).toEqual(sheet);
    // A sheet rotated by almost 45 degrees still starts with the corner closest to the top-left.
    const diamond = [
      { x: 0.5, y: 0.05 },
      { x: 0.95, y: 0.5 },
      { x: 0.5, y: 0.95 },
      { x: 0.05, y: 0.45 },
    ];
    expect(orderCorners(diamond)[0]).toEqual({ x: 0.05, y: 0.45 });
  });

  it("recognizes crossed corners as not convex", () => {
    expect(isConvex(sheet)).toBe(true);
    expect(isConvex([sheet[0], sheet[2], sheet[1], sheet[3]])).toBe(false);
  });

  it("intersects lines, also outside the segments, and refuses parallel ones", () => {
    const crossing = lineIntersection({ x: 0, y: 0 }, { x: 1, y: 0 }, { x: 2, y: -1 }, { x: 2, y: 1 });
    expect(crossing?.x).toBeCloseTo(2);
    expect(crossing?.y).toBeCloseTo(0);
    expect(lineIntersection({ x: 0, y: 0 }, { x: 1, y: 0 }, { x: 0, y: 1 }, { x: 1, y: 1 })).toBeNull();
  });

  it("measures interior angles in pixel space", () => {
    const square: Quad = [
      { x: 0, y: 0 },
      { x: 1, y: 0 },
      { x: 1, y: 1 },
      { x: 0, y: 1 },
    ];
    interiorAngles(square).forEach((angle) => expect(angle).toBeCloseTo(90));
  });

  it("sizes the straightened page from the longer opposite sides and caps it at A4 with 300 dpi", () => {
    expect(
      rectifiedSize([
        { x: 0, y: 0 },
        { x: 1000, y: 0 },
        { x: 1000, y: 1414 },
        { x: 0, y: 1400 },
      ]),
    ).toEqual({ width: 1000, height: 1414 });
    const huge = rectifiedSize([
      { x: 0, y: 0 },
      { x: 4000, y: 0 },
      { x: 4000, y: 6000 },
      { x: 0, y: 6000 },
    ]);
    expect(Math.max(huge.width, huge.height)).toBe(MAX_PAGE_PIXELS);
    expect(huge.width / huge.height).toBeCloseTo(4000 / 6000, 3);
  });

  it("accepts a tilted sheet and a corner slightly outside the photo", () => {
    expect(isPlausibleSheet(sheet, 3000, 4000)).toBe(true);
    const cut: Quad = [{ x: 0.2, y: -0.05 }, sheet[1], sheet[2], sheet[3]];
    expect(isPlausibleSheet(cut, 3000, 4000)).toBe(true);
  });

  it("refuses detections that are more likely a detail than the sheet", () => {
    const small: Quad = [
      { x: 0.4, y: 0.4 },
      { x: 0.6, y: 0.4 },
      { x: 0.6, y: 0.6 },
      { x: 0.4, y: 0.6 },
    ];
    expect(isPlausibleSheet(small, 3000, 4000)).toBe(false);
    const sliver: Quad = [
      { x: 0.1, y: 0.1 },
      { x: 0.9, y: 0.1 },
      { x: 0.95, y: 0.9 },
      { x: 0.93, y: 0.9 },
    ];
    expect(isPlausibleSheet(sliver, 3000, 4000)).toBe(false);
    const farOutside: Quad = [{ x: -0.3, y: 0.1 }, sheet[1], sheet[2], sheet[3]];
    expect(isPlausibleSheet(farOutside, 3000, 4000)).toBe(false);
  });
});
