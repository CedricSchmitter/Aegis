import type { ScanDraft, ScanPage } from "./draft";

/**
 * Keeps a scan in progress in the browser's IndexedDB, so that pages survive Android closing the tab while the
 * camera app is open. Best effort: when IndexedDB is unavailable (private mode, blocked storage) every call quietly
 * does nothing and the scan lives in memory only. The pages are stored separately from the draft record, so a
 * change to one page does not rewrite the photos of all others.
 */
const DATABASE = "aegis-scan";
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

let opening: Promise<IDBDatabase | null> | null = null;

function database(): Promise<IDBDatabase | null> {
  opening ??= new Promise<IDBDatabase | null>((resolve) => {
    try {
      const request = indexedDB.open(DATABASE, VERSION);
      request.onupgradeneeded = () => {
        const db = request.result;
        db.createObjectStore(DRAFTS, { keyPath: "memberId" });
        db.createObjectStore(PAGES, { keyPath: "id" }).createIndex("memberId", "memberId");
      };
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => resolve(null);
      request.onblocked = () => resolve(null);
    } catch {
      resolve(null);
    }
  });
  return opening;
}

function run<T>(
  stores: string[],
  mode: IDBTransactionMode,
  work: (transaction: IDBTransaction) => IDBRequest<T> | void,
): Promise<T | undefined> {
  return database().then(
    (db) =>
      new Promise<T | undefined>((resolve) => {
        if (!db) return resolve(undefined);
        try {
          const transaction = db.transaction(stores, mode);
          const request = work(transaction);
          transaction.oncomplete = () => resolve(request ? request.result : undefined);
          transaction.onerror = () => resolve(undefined);
          transaction.onabort = () => resolve(undefined);
        } catch {
          resolve(undefined);
        }
      }),
  );
}

/** The member's scan in progress with its pages in order, or null when there is none (or storage is unavailable). */
export async function loadDraft(memberId: string): Promise<ScanDraft | null> {
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
}

/** Stores the draft's name and page order, and the given pages (only those that changed need to be passed). */
export async function saveDraft(draft: ScanDraft, changed: readonly ScanPage[]): Promise<void> {
  await run([DRAFTS, PAGES], "readwrite", (tx) => {
    const record: DraftRecord = { memberId: draft.memberId, name: draft.name, pageIds: draft.pages.map((p) => p.id) };
    tx.objectStore(DRAFTS).put(record);
    changed.forEach((page) => tx.objectStore(PAGES).put({ ...page, memberId: draft.memberId } satisfies PageRecord));
  });
}

export async function deletePage(id: string): Promise<void> {
  await run([PAGES], "readwrite", (tx) => {
    tx.objectStore(PAGES).delete(id);
  });
}

/** Removes the member's draft and all its pages, for example after the scan was saved. */
export async function clearDraft(memberId: string): Promise<void> {
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
}
