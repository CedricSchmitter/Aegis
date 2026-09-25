import { useEffect, useRef, useState, type KeyboardEvent, type PointerEvent } from "react";
import { useTranslation } from "react-i18next";
import type { ScanPage } from "../scan/draft";
import { clampCorner, fullQuad, isConvex, type Quad } from "../scan/geometry";
import { useObjectUrl } from "./useObjectUrl";

interface Props {
  page: ScanPage;
  number: number;
  /**
   * `check` right after a photo was taken: shows the detected sheet for a quick "OK", and offers to discard the
   * page. `edit` when a member opens a page later to correct it.
   */
  purpose: "check" | "edit";
  onApply: (corners: Quad) => void;
  onKeepPhoto: () => void;
  onRemove: () => void;
  onClose: () => void;
}

const CORNER_KEYS = ["topLeft", "topRight", "bottomRight", "bottomLeft"] as const;
/** Step of an arrow key on a focused corner, relative to the photo. */
const KEY_STEP = 0.005;

/** Corners to start from when the photo has none: a frame slightly inside the photo, easy to grab. */
function initialCorners(page: ScanPage): Quad {
  const known = page.settings.corners ?? page.result?.corners;
  if (known) return known;
  const inset = 0.08;
  return fullQuad().map((p) => ({ x: p.x === 0 ? inset : 1 - inset, y: p.y === 0 ? inset : 1 - inset })) as Quad;
}

/**
 * Full-screen editor for the four corners of a sheet. Right after a photo is taken it shows the detected sheet for
 * confirmation; later it corrects a detection that missed or chose wrongly. The corners are dragged with a finger
 * (or moved with the arrow keys) on the photo as it was taken.
 */
export function PageEditor({ page, number, purpose, onApply, onKeepPhoto, onRemove, onClose }: Props) {
  const { t } = useTranslation();
  const url = useObjectUrl(page.photo);
  // Null until the member moves a corner; until then the editor follows the detection, which may still be running
  // when the photo is already shown.
  const [edited, setEdited] = useState<Quad | null>(null);
  const corners = edited ?? initialCorners(page);
  const touched = edited !== null;
  const frame = useRef<HTMLDivElement>(null);
  const dragging = useRef<number | null>(null);
  const dialog = useRef<HTMLDivElement>(null);
  const close = useRef(onClose);
  useEffect(() => {
    close.current = onClose;
  });

  const searching = page.result === null && page.settings.corners === null;
  const missing = page.result !== null && page.result.corners === null;
  const checking = purpose === "check";

  // Focus moves into the dialog once when it opens; Escape closes it. The latest onClose is read through a ref, so a
  // re-render (every dragged corner) neither re-registers the listener nor takes the focus from a corner.
  useEffect(() => {
    dialog.current?.focus();
    const onKey = (event: globalThis.KeyboardEvent) => {
      if (event.key === "Escape") close.current();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  const place = (index: number, x: number, y: number) =>
    setEdited((current) => (current ?? corners).map((p, i) => (i === index ? clampCorner({ x, y }) : p)) as Quad);

  const onPointerMove = (event: PointerEvent<HTMLDivElement>) => {
    const index = dragging.current;
    const box = frame.current?.getBoundingClientRect();
    if (index === null || !box) return;
    place(index, (event.clientX - box.left) / box.width, (event.clientY - box.top) / box.height);
  };

  const onCornerKey = (index: number) => (event: KeyboardEvent<HTMLButtonElement>) => {
    const moves: Record<string, [number, number]> = {
      ArrowLeft: [-KEY_STEP, 0],
      ArrowRight: [KEY_STEP, 0],
      ArrowUp: [0, -KEY_STEP],
      ArrowDown: [0, KEY_STEP],
    };
    const move = moves[event.key];
    if (!move) return;
    event.preventDefault();
    place(index, corners[index].x + move[0], corners[index].y + move[1]);
  };

  const valid = isConvex(corners);
  // Unchanged corners of a page that already is a scan need no second processing run.
  const apply = () => (!touched && page.result?.mode === "scan" ? onClose() : onApply(corners));
  const titleId = `page-editor-${page.id}`;

  const title = !checking
    ? t("scan.editor.title", { number })
    : searching
      ? t("scan.page", { number })
      : t(missing ? "scan.editor.missingTitle" : "scan.editor.checkTitle", { number });
  const instructions = searching
    ? t("scan.editor.searching")
    : checking
      ? t(missing ? "scan.editor.missingInstructions" : "scan.editor.checkInstructions")
      : t("scan.editor.instructions");

  return (
    <div
      ref={dialog}
      tabIndex={-1}
      className="fixed inset-0 z-50 flex flex-col gap-3 bg-stone-950 p-4 text-stone-100 outline-none"
      role="dialog"
      aria-modal="true"
      aria-labelledby={titleId}
      data-testid="page-editor"
      data-purpose={purpose}
      data-state={searching ? "searching" : missing ? "missing" : "detected"}
    >
      <div>
        <h2 id={titleId} className="text-lg font-semibold">
          {title}
        </h2>
        <p className="text-sm text-stone-300" aria-live="polite">
          {instructions}
        </p>
      </div>
      <div className="flex min-h-0 flex-1 items-center justify-center px-6 py-4">
        <div
          ref={frame}
          className="relative touch-none select-none"
          onPointerMove={onPointerMove}
          onPointerUp={() => (dragging.current = null)}
          onPointerCancel={() => (dragging.current = null)}
        >
          {url && (
            <img
              src={url}
              alt={t("scan.editor.photo", { number })}
              className="block max-h-[65vh] max-w-full"
              draggable={false}
            />
          )}
          {!searching && (
            <>
              <svg
                className="pointer-events-none absolute inset-0 h-full w-full overflow-visible"
                viewBox="0 0 1 1"
                preserveAspectRatio="none"
                aria-hidden="true"
              >
                <polygon
                  points={corners.map((p) => `${p.x},${p.y}`).join(" ")}
                  fill={valid ? "rgba(56, 189, 248, 0.18)" : "rgba(239, 68, 68, 0.25)"}
                  stroke={valid ? "#38bdf8" : "#ef4444"}
                  strokeWidth={2}
                  vectorEffect="non-scaling-stroke"
                />
              </svg>
              {corners.map((corner, index) => (
                <button
                  key={CORNER_KEYS[index]}
                  type="button"
                  className="absolute h-11 w-11 -translate-x-1/2 -translate-y-1/2 touch-none rounded-full border-2 border-white bg-sky-500/50 shadow"
                  style={{ left: `${corner.x * 100}%`, top: `${corner.y * 100}%` }}
                  aria-label={t(`scan.editor.${CORNER_KEYS[index]}`)}
                  data-testid={`page-editor-corner-${index}`}
                  onPointerDown={(event) => {
                    event.currentTarget.setPointerCapture(event.pointerId);
                    dragging.current = index;
                  }}
                  onKeyDown={onCornerKey(index)}
                />
              ))}
            </>
          )}
        </div>
      </div>
      {!searching && !valid && (
        <p className="text-sm text-red-300" role="alert">
          {t("scan.editor.invalid")}
        </p>
      )}
      <div className="flex flex-wrap gap-2">
        {checking && missing ? (
          <>
            <button type="button" className="btn-primary" onClick={onClose} data-testid="page-editor-keep-photo">
              {t("scan.editor.keepUnchanged")}
            </button>
            <button
              type="button"
              className="btn-secondary"
              disabled={!valid}
              onClick={() => onApply(corners)}
              data-testid="page-editor-apply"
            >
              {t("scan.editor.applyCorners")}
            </button>
          </>
        ) : (
          <>
            <button
              type="button"
              className="btn-primary min-w-28"
              disabled={searching || !valid}
              onClick={apply}
              data-testid="page-editor-apply"
            >
              {t(checking ? "scan.editor.ok" : "scan.editor.apply")}
            </button>
            <button
              type="button"
              className="btn-secondary"
              disabled={searching}
              onClick={() => setEdited(fullQuad())}
            >
              {t("scan.editor.wholePhoto")}
            </button>
            <button
              type="button"
              className="btn-secondary"
              disabled={searching}
              onClick={onKeepPhoto}
              data-testid="page-editor-keep-photo"
            >
              {t("scan.editor.keepPhoto")}
            </button>
          </>
        )}
        {checking ? (
          <button type="button" className="btn-secondary" onClick={onRemove} data-testid="page-editor-remove">
            {t("scan.editor.removePage")}
          </button>
        ) : (
          <button type="button" className="btn-secondary" onClick={onClose}>
            {t("scan.editor.cancel")}
          </button>
        )}
      </div>
    </div>
  );
}
