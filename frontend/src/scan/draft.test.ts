import { describe, expect, it } from "vitest";
import { AUTOMATIC, movePage, nextQuarterTurn, settledSettings, sheetMissing, type ScanPage } from "./draft";

const page = (id: string, result: ScanPage["result"] = null): ScanPage => ({
  id,
  photo: new Blob([id]),
  settings: AUTOMATIC,
  result,
});

const processed = (corners: ScanPage["settings"]["corners"], mode: "scan" | "photo" = corners ? "scan" : "photo") => ({
  page: new Blob(),
  thumbnail: new Blob(),
  width: 10,
  height: 14,
  mode,
  corners,
});

const corners: NonNullable<ScanPage["settings"]["corners"]> = [
  { x: 0.1, y: 0.1 },
  { x: 0.9, y: 0.1 },
  { x: 0.9, y: 0.9 },
  { x: 0.1, y: 0.9 },
];

describe("scan draft", () => {
  it("moves a page and ignores moves past either end", () => {
    const pages = [page("a"), page("b"), page("c")];
    expect(movePage(pages, "c", -1).map((p) => p.id)).toEqual(["a", "c", "b"]);
    expect(movePage(pages, "a", 1).map((p) => p.id)).toEqual(["b", "a", "c"]);
    expect(movePage(pages, "a", -1).map((p) => p.id)).toEqual(["a", "b", "c"]);
    expect(movePage(pages, "c", 1).map((p) => p.id)).toEqual(["a", "b", "c"]);
    expect(movePage(pages, "missing", 1).map((p) => p.id)).toEqual(["a", "b", "c"]);
  });

  it("turns a page a quarter at a time and back to upright", () => {
    expect([0, 1, 2, 3].map((turns) => nextQuarterTurn(turns as 0 | 1 | 2 | 3))).toEqual([1, 2, 3, 0]);
  });

  it("fixes the detected corners after processing, so a later rotation does not detect again", () => {
    expect(settledSettings({ ...AUTOMATIC, quarterTurns: 1 }, processed(corners))).toEqual({
      mode: "scan",
      corners,
      quarterTurns: 1,
    });
    expect(settledSettings(AUTOMATIC, processed(null))).toEqual({ mode: "photo", corners: null, quarterTurns: 0 });
  });

  it("reports a missing sheet only for a processed page without corners", () => {
    expect(sheetMissing(page("a"))).toBe(false);
    expect(sheetMissing(page("a", processed(null)))).toBe(true);
    // The member chose the photo although a sheet was found: that is not a missing sheet.
    expect(sheetMissing(page("a", processed(corners, "photo")))).toBe(false);
  });
});
