import { isPlausibleSheet, lineIntersection, orderCorners, polygonArea, type Point, type Quad } from "./geometry";
import { withMats, type Mat, type OpenCv } from "./opencv";

/** Long side the photo is reduced to for detection: fast on a phone and still precise to a fraction of a percent. */
const DETECT_PIXELS = 640;
/** A polygon side closer than this (in detection pixels) to a photo edge lies on that edge. */
const BORDER_MARGIN = 3;
/** Contour simplification as a share of the perimeter; fine enough to keep a cut-off corner's short sides. */
const SIMPLIFY = 0.004;
/** Neighbouring sides that differ less than this in direction are one side (a bent or wavy sheet edge). */
const STRAIGHT_DEGREES = 12;
/** Sides of the sheet that must be its own edges rather than the photo edge; fewer is the photo, not a sheet. */
const MIN_SHEET_SIDES = 3;

/**
 * Finds the corners of a sheet of paper in an RGBA photo, relative to the photo, or null when no plausible sheet
 * is found. Two masks are tried: a brightness threshold (paper is usually lighter than what it lies on) and the
 * outline of strong edges (for surfaces about as light as the paper). The first plausible result wins.
 */
export function detectSheet(cv: OpenCv, rgba: Mat): Quad | null {
  return withMats((keep) => {
    const scale = Math.min(1, DETECT_PIXELS / Math.max(rgba.cols, rgba.rows));
    const small = keep(new cv.Mat());
    cv.resize(rgba, small, new cv.Size(Math.round(rgba.cols * scale), Math.round(rgba.rows * scale)), 0, 0, cv.INTER_AREA);
    const gray = keep(new cv.Mat());
    cv.cvtColor(small, gray, cv.COLOR_RGBA2GRAY);
    cv.GaussianBlur(gray, gray, new cv.Size(5, 5), 0);

    let best: Quad | null = null;
    for (const mask of MASKS) {
      const corners = withMats((keepMask) => {
        const binary = keepMask(mask(cv, gray));
        const contour = largestContour(cv, binary, keepMask);
        return contour ? quadFromContour(cv, contour, gray.cols, gray.rows, keepMask) : null;
      });
      if (corners && isPlausibleSheet(corners, rgba.cols, rgba.rows) && (!best || area(corners) > area(best))) {
        best = corners;
      }
    }
    return best;
  });
}

const area = (quad: Quad) => Math.abs(polygonArea(quad));

/**
 * The masks tried for every photo. The largest plausible sheet wins: a mask that misses part of the sheet (a shadow
 * across it, a light corner) yields a smaller quad, and cutting off part of a letter is the worst outcome.
 */
const MASKS: ((cv: OpenCv, gray: Mat) => Mat)[] = [
  brightnessMask,
  (cv, gray) => edgeMask(cv, gray, 60, 180),
  (cv, gray) => edgeMask(cv, gray, 25, 75),
];

function brightnessMask(cv: OpenCv, gray: Mat): Mat {
  const binary = new cv.Mat();
  cv.threshold(gray, binary, 0, 255, cv.THRESH_BINARY + cv.THRESH_OTSU);
  // Opening detaches the sheet from light objects that touch it only along a thin line.
  const kernel = cv.getStructuringElement(cv.MORPH_RECT, new cv.Size(7, 7));
  cv.morphologyEx(binary, binary, cv.MORPH_OPEN, kernel);
  kernel.delete();
  return binary;
}

function edgeMask(cv: OpenCv, gray: Mat, low: number, high: number): Mat {
  const edges = new cv.Mat();
  cv.Canny(gray, edges, low, high);
  // Closing the gaps turns the sheet outline into one closed contour.
  const kernel = cv.getStructuringElement(cv.MORPH_RECT, new cv.Size(5, 5));
  cv.morphologyEx(edges, edges, cv.MORPH_CLOSE, kernel);
  kernel.delete();
  return edges;
}

function largestContour(cv: OpenCv, binary: Mat, keep: <M extends { delete(): void }>(value: M) => M): Mat | null {
  const contours = keep(new cv.MatVector());
  const hierarchy = keep(new cv.Mat());
  cv.findContours(binary, contours, hierarchy, cv.RETR_EXTERNAL, cv.CHAIN_APPROX_SIMPLE);
  let best: Mat | null = null;
  let bestArea = 0;
  for (let index = 0; index < contours.size(); index++) {
    const contour = contours.get(index);
    const area = cv.contourArea(contour);
    if (area > bestArea) {
      best?.delete();
      best = contour;
      bestArea = area;
    } else {
      contour.delete();
    }
  }
  return best ? keep(best) : null;
}

/**
 * Four corners from a contour: its convex hull is simplified to a polygon, sides along the photo edge are left out
 * where possible (a sheet corner outside the photo), and the four longest remaining sides are intersected. This also
 * restores corners that are rounded, dog-eared or cut off by the photo edge.
 */
function quadFromContour(
  cv: OpenCv,
  contour: Mat,
  width: number,
  height: number,
  keep: <M extends { delete(): void }>(value: M) => M,
): Quad | null {
  const hull = keep(new cv.Mat());
  cv.convexHull(contour, hull, false, true);
  const polygon = keep(new cv.Mat());
  cv.approxPolyDP(hull, polygon, SIMPLIFY * cv.arcLength(hull, true), true);
  const points: Point[] = [];
  for (let index = 0; index < polygon.rows; index++) {
    points.push({ x: polygon.data32S[index * 2], y: polygon.data32S[index * 2 + 1] });
  }
  if (points.length < 4) return null;
  return quadFromPolygon(points, width, height);
}

interface Side {
  from: Point;
  to: Point;
  order: number;
  length: number;
  onBorder: boolean;
}

/**
 * Removes corners where the outline hardly turns, so that one sheet edge is one side. A corner between a side on
 * the photo edge and a side of the sheet stays: that is where a cut-off sheet corner leaves the photo, often at a
 * shallow angle.
 */
export function mergeStraightRuns(points: readonly Point[], onEdge: (a: Point, b: Point) => boolean): Point[] {
  const kept = [...points];
  let changed = true;
  while (changed && kept.length > 3) {
    changed = false;
    for (let index = 0; index < kept.length; index++) {
      const previous = kept[(index + kept.length - 1) % kept.length];
      const corner = kept[index];
      const next = kept[(index + 1) % kept.length];
      const turn = Math.abs(
        Math.atan2(next.y - corner.y, next.x - corner.x) - Math.atan2(corner.y - previous.y, corner.x - previous.x),
      );
      const degrees = (Math.min(turn, 2 * Math.PI - turn) * 180) / Math.PI;
      if (degrees < STRAIGHT_DEGREES && onEdge(previous, corner) === onEdge(corner, next)) {
        kept.splice(index, 1);
        changed = true;
        break;
      }
    }
  }
  return kept;
}

/** The pure part of quadFromContour, in detection pixels; returns relative corners. */
export function quadFromPolygon(outline: readonly Point[], width: number, height: number): Quad | null {
  const onEdge = (a: Point, b: Point) =>
    (a.x <= BORDER_MARGIN && b.x <= BORDER_MARGIN) ||
    (a.y <= BORDER_MARGIN && b.y <= BORDER_MARGIN) ||
    (a.x >= width - 1 - BORDER_MARGIN && b.x >= width - 1 - BORDER_MARGIN) ||
    (a.y >= height - 1 - BORDER_MARGIN && b.y >= height - 1 - BORDER_MARGIN);
  const points = mergeStraightRuns(outline, onEdge);
  if (points.length < 4) return null;
  const sides: Side[] = points.map((from, order) => {
    const to = points[(order + 1) % points.length];
    return { from, to, order, length: Math.hypot(to.x - from.x, to.y - from.y), onBorder: onEdge(from, to) };
  });
  const inner = sides.filter((side) => !side.onBorder).sort((a, b) => b.length - a.length);
  // Too few edges of its own: the outline is the photo frame (a photo of just paper, or of no sheet at all).
  if (inner.length < MIN_SHEET_SIDES) return null;
  const border = sides.filter((side) => side.onBorder).sort((a, b) => b.length - a.length);
  // A sheet that runs off the photo on a whole side keeps the photo edge as that side.
  const chosen = [...inner, ...border].slice(0, 4).sort((a, b) => a.order - b.order);
  if (chosen.length < 4) return null;
  const corners: Point[] = [];
  for (let index = 0; index < 4; index++) {
    const a = chosen[index];
    const b = chosen[(index + 1) % 4];
    const corner = lineIntersection(a.from, a.to, b.from, b.to);
    if (!corner) return null;
    corners.push({ x: corner.x / width, y: corner.y / height });
  }
  const quad = orderCorners(corners);
  return polygonArea(quad) > 0 ? quad : null;
}
