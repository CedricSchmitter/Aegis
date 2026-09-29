import { useEffect, useRef, useState, type ReactNode, type RefObject } from "react";
import { useTranslation } from "react-i18next";
import type { DocumentDto } from "../../api/types";
import { useSession } from "../../auth/SessionContext";
import { fileKind, thumbnailUrl, viewerSource } from "../../lib/documentView";
import { formatDate } from "../../lib/format";
import { FileTypeTag } from "../ui";

/** Folder path, title and who uploaded the document when (issue #27). */
export function DocumentHead({ document, heading }: { document: DocumentDto; heading: (title: string) => ReactNode }) {
  const { t, i18n } = useTranslation();
  const { memberName } = useSession();
  const path = document.classification.path.map((step) => step.name).join(" / ");
  return (
    <div className="flex min-w-0 flex-col gap-1.5">
      <p className="ui-eyebrow break-words">{path || t("documents.unsorted")}</p>
      {heading(document.title)}
      <p className="flex flex-wrap items-center gap-x-3 gap-y-1 font-mono text-xs text-text-3">
        <FileTypeTag kind={fileKind(document)} />
        <span>
          {t("documents.uploadedBy", {
            name: memberName(document.uploadedBy) ?? t("app.unknownMember"),
            date: formatDate(document.uploadedAt, i18n.language),
          })}
        </span>
      </p>
    </div>
  );
}

/**
 * The archive's image of the first page in the striped preview frame with target brackets. Until the document is
 * archived, or when the archive cannot deliver it, the frame says so instead.
 */
export function DocumentPreview({ document, className = "" }: { document: DocumentDto; className?: string }) {
  const { t } = useTranslation();
  const url = thumbnailUrl(document);
  const [failedUrl, setFailedUrl] = useState<string | null>(null);
  const note =
    document.processing.state === "FAILED" || url !== null
      ? t("documents.preview.none")
      : t("documents.preview.pending");
  return (
    <div className={`ui-paper ui-corners ${className}`} data-testid="document-preview">
      {url !== null && failedUrl !== url ? (
        <img
          src={url}
          alt={t("documents.preview.alt", { title: document.title })}
          className="max-h-[360px] w-auto max-w-full bg-white"
          onError={() => setFailedUrl(url)}
          data-testid="document-thumbnail"
        />
      ) : (
        <p className="px-4 py-10 text-center text-sm text-text-3">{note}</p>
      )}
    </div>
  );
}

/**
 * The document itself on a desktop: the browser's PDF viewer with its own page thumbnails for PDFs, the photo for a
 * photo (ADR-0019). Originals are served from the same origin, which may frame them.
 */
export function DocumentViewer({ document }: { document: DocumentDto }) {
  const { t } = useTranslation();
  const viewer = useRef<HTMLIFrameElement>(null);
  const focused = useFrameFocused(viewer);
  const source = viewerSource(document);
  const frame = "h-[calc(100dvh-16rem)] min-h-[480px] w-full border border-line bg-raised";
  if (source.kind === "pdf") {
    return (
      <iframe
        ref={viewer}
        key={source.url}
        src={source.url}
        data-focused={focused}
        title={t("documents.viewer", { title: document.title })}
        className={frame}
        data-testid="document-viewer"
      />
    );
  }
  if (source.kind === "image") {
    return (
      <div className={`ui-paper overflow-auto ${frame}`} data-testid="document-viewer">
        <img src={source.url} alt={t("documents.preview.alt", { title: document.title })} className="max-w-full" />
      </div>
    );
  }
  return (
    <div className="ui-paper min-h-[240px]" data-testid="document-viewer">
      <p className="px-4 text-center text-sm text-text-3">
        {source.reason === "heic" ? t("documents.heicHint") : t("documents.originalUnavailable")}
      </p>
    </div>
  );
}

/** How long after a Tab press on the page the frame taking the focus still counts as keyboard focus. */
const KEYBOARD_FOCUS_WINDOW_MS = 500;

/**
 * Whether the framed viewer holds the keyboard focus. The page loses the focus to the frame's content without any
 * focus pseudo-class matching the iframe, so the visible focus ring needs this state (ADR-0019, decision 8). Like
 * :focus-visible elsewhere, only keyboard focus counts: a click into the frame reaches the page without a key press.
 */
function useFrameFocused(frame: RefObject<HTMLIFrameElement | null>): boolean {
  const [focused, setFocused] = useState(false);
  useEffect(() => {
    let tabbedAt = Number.NEGATIVE_INFINITY;
    let timer: number | undefined;
    // The active element changes right after the window's blur and focus events.
    const later = (check: () => void) => {
      window.clearTimeout(timer);
      timer = window.setTimeout(check);
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Tab") tabbedAt = event.timeStamp;
    };
    const onEnter = (event: FocusEvent) => {
      const byKeyboard = event.timeStamp - tabbedAt < KEYBOARD_FOCUS_WINDOW_MS;
      later(() => setFocused(byKeyboard && frame.current !== null && window.document.activeElement === frame.current));
    };
    const onLeave = (event: FocusEvent) => {
      // The frame's own focusin comes with the window's blur, in either order.
      if (event.target === frame.current) return;
      later(() => setFocused((current) => current && window.document.activeElement === frame.current));
    };
    window.addEventListener("keydown", onKeyDown, true);
    window.addEventListener("blur", onEnter);
    window.addEventListener("focus", onLeave);
    window.document.addEventListener("focusin", onLeave);
    return () => {
      window.clearTimeout(timer);
      window.removeEventListener("keydown", onKeyDown, true);
      window.removeEventListener("blur", onEnter);
      window.removeEventListener("focus", onLeave);
      window.document.removeEventListener("focusin", onLeave);
    };
  }, [frame]);
  return focused;
}
