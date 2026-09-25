/**
 * Plain geometry of a photographed sheet. Corners are kept relative to the photo (0..1 on both axes), so they stay
 * valid whatever resolution the photo is decoded or displayed at.
 */
export interface Point {
  x: number;
  y: number;
}

/** Corners of a sheet: top-left, top-right, bottom-right, bottom-left. */
export type Quad = [Point, Point, Point, Point];

/** Long side of a processed page: A4 at 300 dpi. Enough for OCR and print, and bounds memory and PDF size. */
export const MAX_PAGE_PIXELS = 3508;

/** The whole photo, used when the member resets the corners. */
export function fullQuad(): Quad {
  return [
    { x: 0, y: 0 },
    { x: 1, y: 0 },
    { x: 1, y: 1 },
    { x: 0, y: 1 },
  ];
}

export function scaleQuad(quad: Quad, width: number, height: number): Quad {
  return quad.map((p) => ({ x: p.x * width, y: p.y * height })) as Quad;
}

/**
 * Puts four points into the order top-left, top-right, bottom-right, bottom-left: sorted by angle around their
 * centroid (clockwise on screen, where y points down), starting with the point that is closest to the top-left.
 */
export function orderCorners(points: readonly Point[]): Quad {
  if (points.length !== 4) throw new Error(`expected 4 corners, got ${points.length}`);
  const cx = points.reduce((sum, p) => sum + p.x, 0) / 4;
  const cy = points.reduce((sum, p) => sum + p.y, 0) / 4;
  const clockwise = [...points].sort((a, b) => Math.atan2(a.y - cy, a.x - cx) - Math.atan2(b.y - cy, b.x - cx));
  let first = 0;
  clockwise.forEach((p, index) => {
    if (p.x + p.y < clockwise[first].x + clockwise[first].y) first = index;
  });
  return [0, 1, 2, 3].map((offset) => clockwise[(first + offset) % 4]) as Quad;
}

/** Area by the shoelace formula; positive for the clockwise-on-screen order used by Quad. */
export function polygonArea(points: readonly Point[]): number {
  let twice = 0;
  points.forEach((p, index) => {
    const next = points[(index + 1) % points.length];
    twice += p.x * next.y - next.x * p.y;
  });
  return twice / 2;
}

/** Interior angles in degrees, one per corner; undefined geometry (a zero-length side) gives 0. */
export function interiorAngles(quad: Quad): number[] {
  return quad.map((corner, index) => {
    const previous = quad[(index + 3) % 4];
    const next = quad[(index + 1) % 4];
    const ax = previous.x - corner.x;
    const ay = previous.y - corner.y;
    const bx = next.x - corner.x;
    const by = next.y - corner.y;
    const lengths = Math.hypot(ax, ay) * Math.hypot(bx, by);
    if (lengths === 0) return 0;
    return (Math.acos(Math.max(-1, Math.min(1, (ax * bx + ay * by) / lengths))) * 180) / Math.PI;
  });
}

/** Whether all turns go the same way, i.e. the quad neither crosses itself nor has a dent. */
export function isConvex(quad: Quad): boolean {
  const signs = quad.map((corner, index) => {
    const next = quad[(index + 1) % 4];
    const after = quad[(index + 2) % 4];
    return Math.sign((next.x - corner.x) * (after.y - next.y) - (next.y - corner.y) * (after.x - next.x));
  });
  return signs.every((sign) => sign > 0) || signs.every((sign) => sign < 0);
}

/** Intersection of the infinite lines through a1-a2 and b1-b2, or null when they are (nearly) parallel. */
export function lineIntersection(a1: Point, a2: Point, b1: Point, b2: Point): Point | null {
  const d = (a1.x - a2.x) * (b1.y - b2.y) - (a1.y - a2.y) * (b1.x - b2.x);
  if (Math.abs(d) < 1e-9) return null;
  const a = a1.x * a2.y - a1.y * a2.x;
  const b = b1.x * b2.y - b1.y * b2.x;
  return {
    x: (a * (b1.x - b2.x) - (a1.x - a2.x) * b) / d,
    y: (a * (b1.y - b2.y) - (a1.y - a2.y) * b) / d,
  };
}

const distance = (a: Point, b: Point) => Math.hypot(a.x - b.x, a.y - b.y);

/**
 * Pixel size of the straightened sheet: the longer of each pair of opposite sides, reduced so that the long side
 * does not exceed MAX_PAGE_PIXELS. A sheet is never enlarged beyond the resolution the photo has.
 */
export function rectifiedSize(corners: Quad): { width: number; height: number } {
  const [tl, tr, br, bl] = corners;
  const width = Math.max(distance(tl, tr), distance(bl, br));
  const height = Math.max(distance(tl, bl), distance(tr, br));
  const scale = Math.min(1, MAX_PAGE_PIXELS / Math.max(width, height));
  return { width: Math.max(1, Math.round(width * scale)), height: Math.max(1, Math.round(height * scale)) };
}

/** Size of a whole photo reduced to MAX_PAGE_PIXELS on its long side, never enlarged. */
export function limitedSize(width: number, height: number): { width: number; height: number } {
  const scale = Math.min(1, MAX_PAGE_PIXELS / Math.max(width, height));
  return { width: Math.max(1, Math.round(width * scale)), height: Math.max(1, Math.round(height * scale)) };
}

/** Smallest share of the photo a detected sheet must cover; anything smaller is more likely a detail on the sheet. */
export const MIN_SHEET_AREA = 0.2;
/** How far a corner may lie outside the photo (as a share of the side), e.g. a sheet corner that was cut off. */
export const MAX_CORNER_OUTSIDE = 0.12;
/** Interior angles a tilted phone produces for a rectangular sheet; flatter or sharper corners are misdetections. */
export const ANGLE_RANGE = { min: 55, max: 125 };

/**
 * Whether relative corners describe a sheet worth straightening: convex, large enough, with plausible angles in
 * pixel space (the photo's aspect ratio matters), and at most slightly outside the photo.
 */
export function isPlausibleSheet(corners: Quad, width: number, height: number): boolean {
  if (!isConvex(corners)) return false;
  const inside = corners.every(
    (p) =>
      p.x >= -MAX_CORNER_OUTSIDE && p.x <= 1 + MAX_CORNER_OUTSIDE && p.y >= -MAX_CORNER_OUTSIDE && p.y <= 1 + MAX_CORNER_OUTSIDE,
  );
  if (!inside) return false;
  if (Math.abs(polygonArea(corners)) < MIN_SHEET_AREA) return false;
  return interiorAngles(scaleQuad(corners, width, height)).every(
    (angle) => angle >= ANGLE_RANGE.min && angle <= ANGLE_RANGE.max,
  );
}

/** Keeps a dragged corner within the area a corner may occupy. */
export function clampCorner(point: Point): Point {
  const clamp = (value: number) => Math.max(-MAX_CORNER_OUTSIDE, Math.min(1 + MAX_CORNER_OUTSIDE, value));
  return { x: clamp(point.x), y: clamp(point.y) };
}
