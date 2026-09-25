/** Diameter of the magnifier shown while a corner is dragged, in CSS pixels. */
export const LOUPE_SIZE = 128;
/** Magnification relative to the photo as displayed in the editor. */
export const LOUPE_ZOOM = 3;
/** Distance between the dragged point and the magnifier, so the fingertip does not cover it. */
const FINGER_GAP = 36;
/** Minimum distance between the magnifier and the edge of the screen. */
const MARGIN = 8;

interface Position {
  x: number;
  y: number;
}

interface Size {
  width: number;
  height: number;
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), Math.max(min, max));
}

/**
 * Top-left position of the magnifier on screen for a dragged point. It sits above the finger, where the hand does
 * not reach; near the top of the screen there is no room above, so it moves beside the point, towards the middle
 * of the screen.
 */
export function loupePlacement(point: Position, viewport: Size, size = LOUPE_SIZE): Position {
  const above = point.y - FINGER_GAP - size;
  if (above >= MARGIN) {
    return { x: clamp(point.x - size / 2, MARGIN, viewport.width - MARGIN - size), y: above };
  }
  const beside = point.x < viewport.width / 2 ? point.x + FINGER_GAP : point.x - FINGER_GAP - size;
  return {
    x: clamp(beside, MARGIN, viewport.width - MARGIN - size),
    y: clamp(point.y - size / 2, MARGIN, viewport.height - MARGIN - size),
  };
}
