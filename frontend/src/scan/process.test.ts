import { afterEach, beforeAll, describe, expect, it } from "vitest";
import { detectSheet } from "./detect";
import { whiten } from "./enhance";
import type { Point, Quad } from "./geometry";
import type { Mat, OpenCv } from "./opencv";
import { processPage } from "./process";
import { testOpenCv } from "./testOpenCv";

let cv: OpenCv;
const created: Mat[] = [];

beforeAll(async () => {
  cv = await testOpenCv();
}, 60_000);

afterEach(() => {
  created.splice(0).forEach((mat) => mat.delete());
});

/**
 * A synthetic phone photo: a light sheet with dark "text lines" on a darker surface, with the sheet corners given
 * relative to the photo (they may lie outside it, as with a corner cut off by the picture edge).
 */
function photo(corners: Quad, options: { surface?: number; paper?: number; width?: number; height?: number } = {}) {
  const width = options.width ?? 900;
  const height = options.height ?? 1200;
  const surface = options.surface ?? 80;
  const paper = options.paper ?? 240;
  const image = new cv.Mat(height, width, cv.CV_8UC4, new cv.Scalar(surface, surface * 0.85, surface * 0.7, 255));
  created.push(image);
  const pixels = corners.map((p) => ({ x: p.x * width, y: p.y * height }));
  fill(image, pixels, paper);
  // Text lines inside the sheet, following its orientation.
  for (let line = 0; line < 12; line++) {
    const top = 0.15 + line * 0.05;
    const at = (u: number, v: number): Point => {
      const [tl, tr, br, bl] = pixels;
      const x = tl.x + (tr.x - tl.x) * u + (bl.x - tl.x) * v + (br.x - bl.x - tr.x + tl.x) * u * v;
      const y = tl.y + (tr.y - tl.y) * u + (bl.y - tl.y) * v + (br.y - bl.y - tr.y + tl.y) * u * v;
      return { x, y };
    };
    fill(image, [at(0.12, top), at(0.75, top), at(0.75, top + 0.015), at(0.12, top + 0.015)], 30);
  }
  return image;
}

function fill(image: Mat, points: Point[], value: number) {
  const polygon = cv.matFromArray(points.length, 1, cv.CV_32SC2, points.flatMap((p) => [Math.round(p.x), Math.round(p.y)]));
  const polygons = new cv.MatVector();
  polygons.push_back(polygon);
  cv.fillPoly(image, polygons, new cv.Scalar(value, value, value, 255));
  polygons.delete();
  polygon.delete();
}

function expectCorners(actual: Quad | null, expected: Quad, tolerance = 0.012) {
  expect(actual).not.toBeNull();
  actual!.forEach((corner, index) => {
    expect(Math.abs(corner.x - expected[index].x), `x of corner ${index}`).toBeLessThan(tolerance);
    expect(Math.abs(corner.y - expected[index].y), `y of corner ${index}`).toBeLessThan(tolerance);
  });
}

const tilted: Quad = [
  { x: 0.22, y: 0.08 },
  { x: 0.9, y: 0.16 },
  { x: 0.8, y: 0.93 },
  { x: 0.1, y: 0.85 },
];

describe("sheet detection", () => {
  it("finds the corners of a tilted sheet on a dark surface", () => {
    expectCorners(detectSheet(cv, photo(tilted)), tilted);
  });

  it("restores a sheet corner that lies outside the photo", () => {
    const cut: Quad = [{ x: 0.2, y: -0.04 }, { x: 0.93, y: 0.05 }, { x: 0.82, y: 0.95 }, { x: 0.08, y: 0.86 }];
    expectCorners(detectSheet(cv, photo(cut)), cut, 0.02);
  });

  it("finds a sheet on a surface nearly as light as the paper by its edges", () => {
    expectCorners(detectSheet(cv, photo(tilted, { surface: 205, paper: 245 })), tilted);
  });

  it("finds no sheet in a photo without one", () => {
    const empty = new cv.Mat(1200, 900, cv.CV_8UC4, new cv.Scalar(120, 110, 100, 255));
    created.push(empty);
    expect(detectSheet(cv, empty)).toBeNull();
  });
});

describe("page processing", () => {
  it("straightens a detected sheet into an upright page with the sheet's proportions", () => {
    const result = processPage(cv, photo(tilted), { mode: null, corners: null, quarterTurns: 0 });
    created.push(result.image);
    expect(result.mode).toBe("scan");
    // Sheet sides in pixels: top ≈ 616, left ≈ 922 (the photo is 900 × 1200).
    expect(result.image.cols).toBeGreaterThan(600);
    expect(result.image.rows).toBeGreaterThan(900);
    expect(result.image.rows / result.image.cols).toBeGreaterThan(1.3);
  });

  it("keeps the whole photo when no sheet is found, and marks the page as such", () => {
    const empty = new cv.Mat(1200, 900, cv.CV_8UC4, new cv.Scalar(120, 110, 100, 255));
    created.push(empty);
    const result = processPage(cv, empty, { mode: null, corners: null, quarterTurns: 0 });
    created.push(result.image);
    expect(result).toMatchObject({ mode: "photo", corners: null });
    expect([result.image.cols, result.image.rows]).toEqual([900, 1200]);
  });

  it("uses the member's corners instead of detecting, and rotates by quarter turns", () => {
    const corners: Quad = [
      { x: 0, y: 0 },
      { x: 0.5, y: 0 },
      { x: 0.5, y: 0.25 },
      { x: 0, y: 0.25 },
    ];
    const result = processPage(cv, photo(tilted), { mode: "scan", corners, quarterTurns: 1 });
    created.push(result.image);
    expect(result.corners).toEqual(corners);
    // 450 × 300 pixels of the photo, turned a quarter.
    expect([result.image.cols, result.image.rows]).toEqual([300, 450]);
  });
});

describe("scan look", () => {
  it("turns unevenly lit paper white and keeps the ink dark", () => {
    const page = new cv.Mat(400, 300, cv.CV_8UC4);
    created.push(page);
    for (let y = 0; y < 400; y++) {
      for (let x = 0; x < 300; x++) {
        // Paper from 230 at the top left to 150 at the bottom right (a shadow), a dark line in the middle.
        const paper = Math.round(230 - (80 * (x + y)) / 700);
        const value = y >= 195 && y < 200 && x > 50 && x < 250 ? 40 : paper;
        page.data.set([value, value, value, 255], (y * 300 + x) * 4);
      }
    }
    const result = whiten(cv, page);
    created.push(result);
    const at = (x: number, y: number) => result.data[(y * 300 + x) * 4];
    expect(at(10, 10)).toBeGreaterThan(245);
    expect(at(290, 390)).toBeGreaterThan(245);
    expect(at(150, 197)).toBeLessThan(90);
  });
});
