import { useCallback, useEffect, useRef, useState } from "react";
import { api, toApiError, unwrap } from "../api/client";
import { requestPageEdit, type PageEditDto } from "../api/pageEdits";
import { pageEditScanDrafts } from "../scan/draftStore";
import { useScanDraft, type ScanDraftState } from "../scan/useScanDraft";
import {
  addDocument,
  addScanPages,
  emptyPlan,
  freshOperation,
  keepScanPages,
  prepareSend,
  scanOrder,
  sourceSubjects,
  type PagePlan,
  type SourceInfo,
  type TitleWords,
} from "./plan";
import { clearPlan, loadPlan, savePlan } from "./planStore";

/** A document that could not be added, with the code of the reason (`pageEdit.source.*` or an API error). */
export interface SourceRefusal {
  documentId: string;
  title: string | null;
  code: string;
}

/** How often an operation in progress is looked at, and after a failed look. */
const POLL_MS = 1500;
const POLL_RETRY_MS = 5000;

export interface PagePlanState {
  /** Null until the stored plan has been looked up. */
  plan: PagePlan | null;
  /** True when a plan with pages was restored from an earlier visit. */
  restored: boolean;
  scan: ScanDraftState;
  /** Applies a change and stores the plan. */
  change(edit: (plan: PagePlan) => PagePlan): void;
  /** Loads the documents' pages and adds them in order; returns the documents that were refused. */
  addDocuments(ids: readonly string[]): Promise<SourceRefusal[]>;
  addingDocuments: boolean;
  /** Adds photos from the camera or the gallery as new pages; returns their ids. */
  addPhotos(photos: readonly File[]): string[];
  /** Removes a new page from the editor once no reference to it is left. */
  forgetScanPage(scanPageId: string): void;
  discard(): Promise<void>;
  submit(words: TitleWords): Promise<void>;
  sending: boolean;
  /** The operation while it runs and once it completed; null before sending and after starting anew. */
  operation: PageEditDto | null;
  /** Error code of the last failed attempt or operation. */
  failure: string | null;
  /** Leaves the result view for an empty editor. */
  startOver(): void;
}

async function loadSource(id: string): Promise<SourceInfo | SourceRefusal> {
  try {
    const [document, pages] = await Promise.all([
      api.GET("/api/documents/{id}", { params: { path: { id } } }).then(unwrap),
      api.GET("/api/documents/{id}/pages", { params: { path: { id } } }).then(unwrap),
    ]);
    if (pages.refusal || pages.pages.length === 0) {
      return { documentId: id, title: document.title, code: pages.refusal ?? "pageEdit.source.unreadable" };
    }
    return {
      documentId: id,
      title: document.title,
      categoryId: document.classification.categoryId ?? null,
      subjects: sourceSubjects(document.subjects),
      sourceKind: pages.sourceKind,
      pages: pages.pages.map((page) => ({ width: page.width, height: page.height })),
      warnings: pages.warnings,
    };
  } catch (failure) {
    return { documentId: id, title: null, code: toApiError(failure).code };
  }
}

/**
 * The page editor's plan in progress of the signed-in member (ADR-0024): stored in IndexedDB on every change together
 * with the new pages, sent as one durable request and followed until the new documents exist. `planRef` is the source
 * of truth; React state mirrors it for rendering, so asynchronous results apply to the latest plan.
 */
export function usePagePlan(memberId: string | null): PagePlanState {
  const [plan, setPlan] = useState<PagePlan | null>(null);
  const [restored, setRestored] = useState(false);
  const [addingDocuments, setAddingDocuments] = useState(false);
  const [sending, setSending] = useState(false);
  const [operation, setOperation] = useState<PageEditDto | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const planRef = useRef<PagePlan | null>(null);
  const scan = useScanDraft(memberId, () => "page-edit", pageEditScanDrafts);
  const scanRef = useRef(scan);
  useEffect(() => {
    scanRef.current = scan;
  });

  const commit = useCallback((next: PagePlan) => {
    planRef.current = next;
    setPlan(next);
    void savePlan(next);
  }, []);

  const change = useCallback(
    (edit: (current: PagePlan) => PagePlan) => {
      if (!planRef.current) return;
      commit(edit(planRef.current));
    },
    [commit],
  );

  useEffect(() => {
    if (!memberId) return;
    let active = true;
    void loadPlan(memberId).then((stored) => {
      if (!active || planRef.current) return;
      const next = stored ?? emptyPlan(memberId);
      planRef.current = next;
      setPlan(next);
      setRestored(!!stored && stored.outputs.length > 0);
    });
    return () => {
      active = false;
    };
  }, [memberId]);

  // References to new pages that are gone (a photo the browser could not open) are dropped from the plan.
  const scanIds = scan.pages.map((page) => page.id).join(",");
  const planLoaded = plan !== null;
  useEffect(() => {
    if (!scan.ready || !planLoaded || !planRef.current) return;
    const existing = scanIds ? scanIds.split(",") : [];
    const kept = keepScanPages(planRef.current, existing);
    if (kept !== planRef.current) commit(kept);
    // New pages the plan does not use (left over from an interrupted visit) are not kept.
    const used = scanOrder(kept);
    existing.filter((id) => !used.includes(id)).forEach((id) => scanRef.current.remove(id));
  }, [commit, planLoaded, scan.ready, scanIds]);

  const addDocuments = useCallback(
    async (ids: readonly string[]) => {
      setAddingDocuments(true);
      const refusals: SourceRefusal[] = [];
      try {
        // Loaded one after another, so the pages are added in the order the documents were chosen.
        for (const id of ids) {
          const source = await loadSource(id);
          if ("code" in source) refusals.push(source);
          else change((current) => addDocument(current, source));
        }
      } finally {
        setAddingDocuments(false);
      }
      return refusals;
    },
    [change],
  );

  const addPhotos = useCallback(
    (photos: readonly File[]) => {
      const added = scanRef.current.addPhotos(photos);
      if (added.length > 0) change((current) => addScanPages(current, added));
      return added;
    },
    [change],
  );

  const forgetScanPage = useCallback((scanPageId: string) => {
    if (planRef.current && scanOrder(planRef.current).includes(scanPageId)) return;
    scanRef.current.remove(scanPageId);
  }, []);

  const reset = useCallback(async () => {
    if (!memberId) return;
    commit(emptyPlan(memberId));
    setRestored(false);
    setFailure(null);
    await Promise.all([clearPlan(memberId), scanRef.current.clear()]);
  }, [commit, memberId]);

  const submit = useCallback(
    async (words: TitleWords) => {
      const current = planRef.current;
      if (!current) return;
      setSending(true);
      setFailure(null);
      try {
        const order = scanOrder(current);
        const signature = JSON.stringify(
          order.map((id) => scanRef.current.pages.find((page) => page.id === id)?.settings ?? null),
        );
        const { plan: prepared, request } = prepareSend(current, words, signature);
        // The id and what is sent under it are stored first, so a reload after a lost answer sends the same request.
        commit(prepared);
        // The same new pages give the same bytes, so a repeated request carries an identical attachment.
        const pdf = order.length > 0 ? await scanRef.current.toPdf(order) : null;
        const accepted = await requestPageEdit(request, pdf);
        setOperation(accepted);
        change((latest) => ({ ...latest, pending: true }));
      } catch (error) {
        const code = toApiError(error).code;
        if (code === "pageEdit.conflict") change((latest) => freshOperation(latest));
        setFailure(code);
      } finally {
        setSending(false);
      }
    },
    [change, commit],
  );

  // Follows an accepted operation until the new documents exist or it failed, also after a reload.
  const pendingId = plan?.pending ? plan.operationId : null;
  useEffect(() => {
    if (!pendingId) return;
    let active = true;
    let timer = 0;
    const look = async () => {
      let delay = POLL_MS;
      try {
        const latest = unwrap(await api.GET("/api/page-edits/{id}", { params: { path: { id: pendingId } } }));
        if (!active) return;
        setOperation(latest);
        if (latest.state === "COMPLETED") {
          await reset();
          return;
        }
        if (latest.state === "FAILED") {
          change((current) => freshOperation(current));
          setFailure(latest.failureCode ?? "generic");
          return;
        }
      } catch {
        delay = POLL_RETRY_MS;
      }
      if (active) timer = window.setTimeout(() => void look(), delay);
    };
    void look();
    return () => {
      active = false;
      window.clearTimeout(timer);
    };
  }, [change, pendingId, reset]);

  return {
    plan,
    restored,
    scan,
    change,
    addDocuments,
    addingDocuments,
    addPhotos,
    forgetScanPage,
    discard: async () => {
      setOperation(null);
      await reset();
    },
    submit,
    sending,
    operation,
    failure,
    startOver: () => {
      setOperation(null);
      setFailure(null);
    },
  };
}
