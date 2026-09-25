import type { QuarterTurns } from "./enhance";
import type { PageSettings } from "./process";
import type { ProcessedResult } from "./processor";

/** Upper bound of pages in one scan; a longer document is better split, and it bounds the PDF size. */
export const MAX_PAGES = 30;

/** One page of a scan in progress: the photo as taken, how to process it, and the result once processed. */
export interface ScanPage {
  id: string;
  photo: Blob;
  settings: PageSettings;
  /** Null while the page is processed. */
  result: ProcessedResult | null;
}

/** A scan in progress of one member: its document name and the pages in order. */
export interface ScanDraft {
  memberId: string;
  name: string;
  pages: ScanPage[];
}

/** Settings for a newly added photo: detect the sheet automatically, upright as taken. */
export const AUTOMATIC: PageSettings = { mode: null, corners: null, quarterTurns: 0 };

/** Whether automatic detection found no sheet in this page's photo (and it therefore kept the photo). */
export function sheetMissing(page: ScanPage): boolean {
  return page.result !== null && page.result.corners === null;
}

/** Moves a page by `delta` places; out-of-range moves leave the order unchanged. */
export function movePage(pages: readonly ScanPage[], id: string, delta: number): ScanPage[] {
  const from = pages.findIndex((page) => page.id === id);
  const to = from + delta;
  if (from < 0 || to < 0 || to >= pages.length) return [...pages];
  const moved = [...pages];
  const [page] = moved.splice(from, 1);
  moved.splice(to, 0, page);
  return moved;
}

export function nextQuarterTurn(turns: QuarterTurns): QuarterTurns {
  return ((turns + 1) % 4) as QuarterTurns;
}

/**
 * Settings that reproduce a processed page exactly, so that a later change (rotation, corners) does not run the
 * automatic detection again and silently move the corners.
 */
export function settledSettings(settings: PageSettings, result: ProcessedResult): PageSettings {
  return { mode: result.mode, corners: result.corners, quarterTurns: settings.quarterTurns };
}
