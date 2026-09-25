import { detectSheet } from "./detect";
import { limit, rectify, rotate, whiten, type QuarterTurns } from "./enhance";
import type { Quad } from "./geometry";
import type { Mat, OpenCv } from "./opencv";

/**
 * How a page is made from its photo. `scan` straightens the sheet inside the corners and gives it the scan look;
 * `photo` keeps the whole photo unchanged apart from the size limit.
 */
export type PageMode = "scan" | "photo";

export interface PageSettings {
  /** Null asks for automatic detection; a page that has no plausible sheet then keeps the photo. */
  mode: PageMode | null;
  /** Relative corners for `scan`; null together with mode null. */
  corners: Quad | null;
  quarterTurns: QuarterTurns;
}

export interface ProcessedPage {
  /** RGBA result, owned by the caller. */
  image: Mat;
  mode: PageMode;
  /** The corners used for `scan`, or the ones detected even when the member chose `photo`; null if none. */
  corners: Quad | null;
}

/** Makes one page from an RGBA photo. Never throws for a photo without a sheet: it falls back to the photo. */
export function processPage(cv: OpenCv, photo: Mat, settings: PageSettings): ProcessedPage {
  const corners = settings.corners ?? detectSheet(cv, photo);
  const mode: PageMode = settings.mode ?? (corners ? "scan" : "photo");
  let page: Mat;
  if (mode === "scan" && corners) {
    const straight = rectify(cv, photo, corners);
    try {
      page = whiten(cv, straight);
    } finally {
      straight.delete();
    }
  } else {
    page = limit(cv, photo);
  }
  try {
    return { image: rotate(cv, page, settings.quarterTurns), mode: corners ? mode : "photo", corners };
  } finally {
    page.delete();
  }
}
