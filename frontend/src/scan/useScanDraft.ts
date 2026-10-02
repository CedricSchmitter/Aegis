import { useCallback, useEffect, useRef, useState } from "react";
import { AUTOMATIC, MAX_PAGES, movePage, nextQuarterTurn, settledSettings, type ScanDraft, type ScanPage } from "./draft";
import { uploadScanDrafts, type DraftStore } from "./draftStore";
import type { Quad } from "./geometry";
import { buildPdf } from "./pdf";
import type { PageSettings } from "./process";
import { PageProcessor, ProcessingError } from "./processor";

/** Why pages could not be added: a photo the browser cannot open, a processing error, or the page limit. */
export type ScanProblem = "decode" | "process" | "tooMany";

export interface ScanDraftState {
  pages: ScanPage[];
  /** Name of the document the scan becomes, fixed when its first page was added. */
  name: string | null;
  /** Number of pages restored from an earlier visit (0 if none), for a notice. */
  restored: number;
  /** False until a scan stored on an earlier visit has been looked up. */
  ready: boolean;
  problem: ScanProblem | null;
  /** True while any page is being processed; the scan cannot be saved then. */
  processing: boolean;
  /** Adds the photos as pages and returns their ids (fewer than given when the page limit is reached). */
  addPhotos(photos: readonly File[]): string[];
  rotate(id: string): void;
  move(id: string, delta: number): void;
  remove(id: string): void;
  /** Straighten the sheet inside these corners (relative to the photo). */
  applyCorners(id: string, corners: Quad): void;
  /** Keep the whole photo without the scan look. */
  keepPhoto(id: string): void;
  clear(): Promise<void>;
  /**
   * The scan as one PDF file; only when no page is being processed. `order` names the pages to include and their
   * order (the page editor arranges new pages itself); without it, all pages in the scan's order.
   */
  toPdf(order?: readonly string[]): Promise<File>;
}

/**
 * The scan in progress of the signed-in member: pages are added from the camera or the gallery, processed one
 * after another in a worker and kept in IndexedDB until the scan is saved or discarded. `draftRef` is the source of
 * truth; React state mirrors it for rendering, so asynchronous results always apply to the latest pages.
 */
export function useScanDraft(
  memberId: string | null,
  newName: () => string,
  store: DraftStore = uploadScanDrafts,
): ScanDraftState {
  const { loadDraft, saveDraft, deletePage, clearDraft } = store;
  const [draft, setDraft] = useState<ScanDraft | null>(null);
  const [restored, setRestored] = useState(0);
  const [ready, setReady] = useState(false);
  const [problem, setProblem] = useState<ScanProblem | null>(null);
  const draftRef = useRef<ScanDraft | null>(null);
  const processor = useRef<PageProcessor | null>(null);

  const commit = useCallback((next: ScanDraft | null, changed: readonly ScanPage[] = []) => {
    draftRef.current = next;
    setDraft(next);
    if (next) void saveDraft(next, changed);
  }, [saveDraft]);

  const replacePage = useCallback(
    (id: string, change: (page: ScanPage) => ScanPage): ScanPage | null => {
      const current = draftRef.current;
      const page = current?.pages.find((candidate) => candidate.id === id);
      if (!current || !page) return null;
      const updated = change(page);
      commit({ ...current, pages: current.pages.map((candidate) => (candidate.id === id ? updated : candidate)) }, [
        updated,
      ]);
      return updated;
    },
    [commit],
  );

  const removePage = useCallback(
    (id: string) => {
      const current = draftRef.current;
      if (!current) return;
      const pages = current.pages.filter((page) => page.id !== id);
      void deletePage(id);
      if (pages.length === 0 && memberId) {
        draftRef.current = null;
        setDraft(null);
        void clearDraft(memberId);
      } else {
        commit({ ...current, pages });
      }
    },
    [clearDraft, commit, deletePage, memberId],
  );

  const processPage = useCallback(
    (page: ScanPage) => {
      processor.current ??= new PageProcessor();
      const requested = page.settings;
      processor.current.process(page.photo, requested).then(
        (result) => {
          // A newer request (another rotation, new corners) replaced these settings; its result will follow.
          replacePage(page.id, (latest) =>
            latest.settings === requested ? { ...latest, settings: settledSettings(requested, result), result } : latest,
          );
        },
        (failure: unknown) => {
          const latest = draftRef.current?.pages.find((candidate) => candidate.id === page.id);
          if (!latest || latest.settings !== requested) return;
          setProblem(failure instanceof ProcessingError && failure.reason === "decode" ? "decode" : "process");
          removePage(page.id);
        },
      );
    },
    [removePage, replacePage],
  );

  const reprocess = useCallback(
    (id: string, settings: PageSettings) => {
      const updated = replacePage(id, (page) => ({ ...page, settings, result: null }));
      if (updated) processPage(updated);
    },
    [processPage, replacePage],
  );

  useEffect(() => {
    if (!memberId) return;
    let active = true;
    void loadDraft(memberId).then((stored) => {
      if (!active) return;
      setReady(true);
      if (!stored || draftRef.current) return;
      commit(stored);
      setRestored(stored.pages.length);
      // Pages whose processing was interrupted by the reload are processed again.
      stored.pages.filter((page) => page.result === null).forEach(processPage);
    });
    return () => {
      active = false;
    };
  }, [commit, loadDraft, memberId, processPage]);

  useEffect(
    () => () => {
      processor.current?.dispose();
      processor.current = null;
    },
    [],
  );

  const addPhotos = useCallback(
    (photos: readonly File[]) => {
      if (!memberId || photos.length === 0) return [];
      setProblem(null);
      setRestored(0);
      const current = draftRef.current ?? { memberId, name: newName(), pages: [] };
      const room = Math.max(0, MAX_PAGES - current.pages.length);
      if (photos.length > room) setProblem("tooMany");
      const added: ScanPage[] = photos
        .slice(0, room)
        .map((photo) => ({ id: crypto.randomUUID(), photo, settings: AUTOMATIC, result: null }));
      if (added.length === 0) return [];
      commit({ ...current, pages: [...current.pages, ...added] }, added);
      added.forEach(processPage);
      return added.map((page) => page.id);
    },
    [commit, memberId, newName, processPage],
  );

  const pages = draft?.pages ?? [];
  return {
    pages,
    name: draft?.name ?? null,
    restored,
    ready,
    problem,
    processing: pages.some((page) => page.result === null),
    addPhotos,
    rotate: (id) => {
      const page = draftRef.current?.pages.find((candidate) => candidate.id === id);
      if (page) reprocess(id, { ...page.settings, quarterTurns: nextQuarterTurn(page.settings.quarterTurns) });
    },
    move: (id, delta) => {
      const current = draftRef.current;
      if (!current) return;
      commit({ ...current, pages: movePage(current.pages, id, delta) });
    },
    remove: (id) => {
      setProblem(null);
      removePage(id);
    },
    applyCorners: (id, corners) => {
      const page = draftRef.current?.pages.find((candidate) => candidate.id === id);
      if (page) reprocess(id, { mode: "scan", corners, quarterTurns: page.settings.quarterTurns });
    },
    keepPhoto: (id) => {
      const page = draftRef.current?.pages.find((candidate) => candidate.id === id);
      if (page) reprocess(id, { ...page.settings, mode: "photo" });
    },
    clear: async () => {
      processor.current?.dispose();
      processor.current = null;
      draftRef.current = null;
      setDraft(null);
      setProblem(null);
      setRestored(0);
      if (memberId) await clearDraft(memberId);
    },
    toPdf: async (order) => {
      const current = draftRef.current;
      const chosen = order
        ? order.map((id) => current?.pages.find((page) => page.id === id))
        : (current?.pages ?? []);
      if (!current || chosen.length === 0 || chosen.some((page) => !page || page.result === null)) {
        throw new Error("scan is not ready");
      }
      const pdfPages = await Promise.all(
        (chosen as ScanPage[]).map(async (page) => ({
          jpeg: new Uint8Array(await page.result!.page.arrayBuffer()),
          width: page.result!.width,
          height: page.result!.height,
        })),
      );
      return new File([buildPdf(pdfPages)], `${current.name}.pdf`, { type: "application/pdf" });
    },
  };
}
