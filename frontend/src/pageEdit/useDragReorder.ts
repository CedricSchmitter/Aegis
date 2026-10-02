import { useCallback, useEffect, useRef, useState, type PointerEvent } from "react";

/** Where a dragged page lands: before the page at `position` of the document `outputKey`. */
export interface DropTarget {
  outputKey: string;
  position: number;
}

/** Distance from the top or bottom edge of the window within which a drag scrolls the page, and the step. */
const EDGE = 72;
const STEP = 14;

/**
 * The drop target under a point: a page row (`data-drop-output`, `data-drop-position`) takes the page before or after
 * it depending on which half the point is in; a document's header or a split marker (`data-drop-edge="exact"`) takes
 * it exactly at its position.
 */
export function dropTargetAt(x: number, y: number): DropTarget | null {
  for (const element of document.elementsFromPoint(x, y)) {
    const target = element.closest<HTMLElement>("[data-drop-output]");
    if (!target) continue;
    const outputKey = target.dataset.dropOutput!;
    const position = Number(target.dataset.dropPosition ?? 0);
    if (target.dataset.dropEdge === "exact") return { outputKey, position };
    const box = target.getBoundingClientRect();
    return { outputKey, position: y < box.top + box.height / 2 ? position : position + 1 };
  }
  return null;
}

/**
 * Drag and drop of pages with a handle, for touch and mouse alike: the handle captures the pointer, the page under
 * the finger becomes the target, and near the window's edges the page scrolls. The move buttons remain the
 * accessible way to reorder.
 */
export function useDragReorder(onDrop: (pageKey: string, target: DropTarget) => void) {
  const [dragging, setDragging] = useState<string | null>(null);
  const [target, setTarget] = useState<DropTarget | null>(null);
  const pointer = useRef<{ x: number; y: number } | null>(null);
  const targetRef = useRef<DropTarget | null>(null);

  const aim = useCallback((x: number, y: number) => {
    pointer.current = { x, y };
    const next = dropTargetAt(x, y);
    targetRef.current = next;
    setTarget((current) =>
      current?.outputKey === next?.outputKey && current?.position === next?.position ? current : next,
    );
  }, []);

  // While a finger rests near an edge, the page keeps scrolling and the target follows.
  useEffect(() => {
    if (!dragging) return;
    const timer = window.setInterval(() => {
      const at = pointer.current;
      if (!at) return;
      const delta = at.y < EDGE ? -STEP : at.y > window.innerHeight - EDGE ? STEP : 0;
      if (delta === 0) return;
      window.scrollBy(0, delta);
      aim(at.x, at.y);
    }, 30);
    return () => window.clearInterval(timer);
  }, [aim, dragging]);

  const end = () => {
    setDragging(null);
    setTarget(null);
    targetRef.current = null;
    pointer.current = null;
  };

  const handleProps = (pageKey: string, disabled = false) => ({
    onPointerDown: (event: PointerEvent<HTMLElement>) => {
      if (disabled || event.button !== 0) return;
      event.preventDefault();
      event.currentTarget.setPointerCapture(event.pointerId);
      setDragging(pageKey);
      aim(event.clientX, event.clientY);
    },
    onPointerMove: (event: PointerEvent<HTMLElement>) => {
      if (dragging === pageKey) aim(event.clientX, event.clientY);
    },
    onPointerUp: () => {
      const landing = targetRef.current;
      if (dragging === pageKey && landing) onDrop(pageKey, landing);
      end();
    },
    onPointerCancel: end,
  });

  return { dragging, target, handleProps };
}
