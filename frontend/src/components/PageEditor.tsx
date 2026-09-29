import { useRef, useState, type KeyboardEvent, type PointerEvent } from "react";
import { useTranslation } from "react-i18next";
import type { ScanPage } from "../scan/draft";
import { clampCorner, fullQuad, isConvex, type Quad } from "../scan/geometry";
import { LOUPE_SIZE, LOUPE_ZOOM, loupePlacement } from "./loupe";
import { Overlay } from "./ui";
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

/** A corner being dragged, with the photo's place on screen when the drag started. */
interface Drag {
  index: number;
  frame: { left: number; top: number; width: number; height: number };
  viewport: { width: number; height: number };
}

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
  const [drag, setDrag] = useState<Drag | null>(null);

  const searching = page.result === null && page.settings.corners === null;
  const missing = page.result !== null && page.result.corners === null;
  const checking = purpose === "check";

  const place = (index: number, x: number, y: number) =>
    setEdited((current) => (current ?? corners).map((p, i) => (i === index ? clampCorner({ x, y }) : p)) as Quad);

  const onPointerMove = (event: PointerEvent<HTMLDivElement>) => {
    const box = frame.current?.getBoundingClientRect();
    if (drag === null || !box) return;
    place(drag.index, (event.clientX - box.left) / box.width, (event.clientY - box.top) / box.height);
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

  // The editor covers the whole screen as a modal of its own, above the upload sheet it is opened from. Escape closes
  // the editor only; the sheet stays open with its pages.
  return (
    <Overlay
      onClose={onClose}
      className="gap-3"
      aria-labelledby={titleId}
      data-testid="page-editor"
      data-page-editor
      data-purpose={purpose}
      data-state={searching ? "searching" : missing ? "missing" : "detected"}
    >
      <div>
        <h2 id={titleId} className="text-lg font-semibold">
          {title}
        </h2>
        <p className="text-sm text-text-3" aria-live="polite">
          {instructions}
        </p>
      </div>
      <div className="flex min-h-0 flex-1 items-center justify-center px-6 py-4">
        <div
          ref={frame}
          className="relative touch-none select-none"
          onPointerMove={onPointerMove}
          onPointerUp={() => setDrag(null)}
          onPointerCancel={() => setDrag(null)}
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
              <Outline corners={corners} valid={valid} />
              {corners.map((corner, index) => (
                <button
                  key={CORNER_KEYS[index]}
                  type="button"
                  className="absolute h-11 w-11 -translate-x-1/2 -translate-y-1/2 touch-none rounded-full border-2 border-text-1 bg-data/50 ring-1 ring-void/60"
                  style={{ left: `${corner.x * 100}%`, top: `${corner.y * 100}%` }}
                  aria-label={t(`scan.editor.${CORNER_KEYS[index]}`)}
                  data-testid={`page-editor-corner-${index}`}
                  onPointerDown={(event) => {
                    event.currentTarget.setPointerCapture(event.pointerId);
                    const box = frame.current?.getBoundingClientRect();
                    if (!box) return;
                    setDrag({
                      index,
                      frame: { left: box.left, top: box.top, width: box.width, height: box.height },
                      viewport: { width: window.innerWidth, height: window.innerHeight },
                    });
                  }}
                  onKeyDown={onCornerKey(index)}
                />
              ))}
            </>
          )}
        </div>
      </div>
      {drag && url && <Loupe url={url} corners={corners} drag={drag} valid={valid} />}
      {!searching && !valid && (
        <p className="text-sm text-danger" role="alert">
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
    </Overlay>
  );
}

/** The sheet as the four corners frame it, drawn over the photo. */
function Outline({ corners, valid }: { corners: Quad; valid: boolean }) {
  return (
    <svg
      className="pointer-events-none absolute inset-0 h-full w-full overflow-visible"
      viewBox="0 0 1 1"
      preserveAspectRatio="none"
      aria-hidden="true"
    >
      <polygon
        points={corners.map((p) => `${p.x},${p.y}`).join(" ")}
        className={valid ? "fill-data/20 stroke-data" : "fill-danger/25 stroke-danger"}
        strokeWidth={2}
        vectorEffect="non-scaling-stroke"
      />
    </svg>
  );
}

/**
 * Magnified view of the dragged corner, shown beside the finger that covers it. The crosshair in the middle marks
 * exactly where the corner lands on the photo.
 */
function Loupe({ url, corners, drag, valid }: { url: string; corners: Quad; drag: Drag; valid: boolean }) {
  const corner = corners[drag.index];
  const { frame } = drag;
  const point = { x: frame.left + corner.x * frame.width, y: frame.top + corner.y * frame.height };
  const position = loupePlacement(point, drag.viewport);
  return (
    <div
      className="pointer-events-none fixed z-10 overflow-hidden rounded-full border-2 border-text-1 bg-surface ring-1 ring-void/60"
      style={{ left: position.x, top: position.y, width: LOUPE_SIZE, height: LOUPE_SIZE }}
      aria-hidden="true"
      data-testid="page-editor-loupe"
    >
      <div
        className="absolute"
        style={{
          left: LOUPE_SIZE / 2 - corner.x * frame.width * LOUPE_ZOOM,
          top: LOUPE_SIZE / 2 - corner.y * frame.height * LOUPE_ZOOM,
          width: frame.width * LOUPE_ZOOM,
          height: frame.height * LOUPE_ZOOM,
        }}
      >
        <img src={url} alt="" className="block h-full w-full" draggable={false} />
        <Outline corners={corners} valid={valid} />
      </div>
      {/* Light on a dark outline, so the crosshair stays visible on paper and on a dark desk alike. */}
      <svg className="absolute inset-0 h-full w-full" viewBox="-1 -1 2 2" fill="none">
        {[
          { stroke: "stroke-void/60", width: 0.04 },
          { stroke: "stroke-text-1", width: 0.018 },
        ].map(({ stroke, width }) => (
          <g key={stroke} className={stroke} strokeWidth={width} strokeLinecap="round">
            <circle r={0.12} />
            <path d="M -0.4 0 H -0.12 M 0.12 0 H 0.4 M 0 -0.4 V -0.12 M 0 0.12 V 0.4" />
          </g>
        ))}
        <circle r={0.018} className={valid ? "fill-data" : "fill-danger"} />
      </svg>
    </div>
  );
}
