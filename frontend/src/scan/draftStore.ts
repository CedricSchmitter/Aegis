import { indexedDb } from "../lib/indexedDb";
import type { ScanDraft, ScanPage } from "./draft";

/**
 * Keeps a scan in progress in the browser's IndexedDB, so that pages survive Android closing the tab while the
 * camera app is open. Best effort: when IndexedDB is unavailable (private mode, blocked storage) every call quietly
 * does nothing and the scan lives in memory only. The pages are stored separately from the draft record, so a
 * change to one page does not rewrite the photos of all others.
 */
const VERSION = 1;
const DRAFTS = "drafts";
const PAGES = "pages";

interface DraftRecord {
  memberId: string;
  name: string;
  pageIds: string[];
}

interface PageRecord extends ScanPage {
  memberId: string;
}

export interface DraftStore {
  /** The member's scan in progress with its pages in order, or null when there is none (or storage is unavailable). */
  loadDraft(memberId: string): Promise<ScanDraft | null>;
  /** Stores the draft's name and page order, and the given pages (only those that changed need to be passed). */
  saveDraft(draft: ScanDraft, changed: readonly ScanPage[]): Promise<void>;
  deletePage(id: string): Promise<void>;
  /** Removes the member's draft and all its pages, for example after the scan was saved. */
  clearDraft(memberId: string): Promise<void>;
}

/** A store of scans in progress in its own database, so that the upload's scan and the page editor's new pages never mix. */
export function createDraftStore(database: string): DraftStore {
  const run = indexedDb(database, VERSION, (db) => {
    db.createObjectStore(DRAFTS, { keyPath: "memberId" });
    db.createObjectStore(PAGES, { keyPath: "id" }).createIndex("memberId", "memberId");
  });

  return {
    async loadDraft(memberId) {
      const draft = await run<DraftRecord>([DRAFTS], "readonly", (tx) => tx.objectStore(DRAFTS).get(memberId));
      if (!draft) return null;
      const records =
        (await run<PageRecord[]>([PAGES], "readonly", (tx) =>
          tx.objectStore(PAGES).index("memberId").getAll(memberId),
        )) ?? [];
      const byId = new Map(records.map((record) => [record.id, record]));
      const pages = draft.pageIds.flatMap((id) => {
        const record = byId.get(id);
        if (!record) return [];
        const { id: pageId, photo, settings, result } = record;
        return [{ id: pageId, photo, settings, result }];
      });
      return pages.length > 0 ? { memberId, name: draft.name, pages } : null;
    },

    async saveDraft(draft, changed) {
      await run([DRAFTS, PAGES], "readwrite", (tx) => {
        const record: DraftRecord = { memberId: draft.memberId, name: draft.name, pageIds: draft.pages.map((p) => p.id) };
        tx.objectStore(DRAFTS).put(record);
        changed.forEach((page) => tx.objectStore(PAGES).put({ ...page, memberId: draft.memberId } satisfies PageRecord));
      });
    },

    async deletePage(id) {
      await run([PAGES], "readwrite", (tx) => {
        tx.objectStore(PAGES).delete(id);
      });
    },

    async clearDraft(memberId) {
      await run([DRAFTS, PAGES], "readwrite", (tx) => {
        tx.objectStore(DRAFTS).delete(memberId);
        const pages = tx.objectStore(PAGES);
        const request = pages.index("memberId").openKeyCursor(IDBKeyRange.only(memberId));
        request.onsuccess = () => {
          const cursor = request.result;
          if (cursor) {
            pages.delete(cursor.primaryKey);
            cursor.continue();
          }
        };
      });
    },
  };
}

/** The scan of the upload sheet (ADR-0016). */
export const uploadScanDrafts = createDraftStore("aegis-scan");
/** New pages photographed in the page editor (ADR-0024). */
export const pageEditScanDrafts = createDraftStore("aegis-page-edit-scan");

export const { loadDraft, saveDraft, deletePage, clearDraft } = uploadScanDrafts;
