/**
 * Best-effort access to one IndexedDB database of the browser. When IndexedDB is unavailable (private mode, blocked
 * storage) every call resolves with `undefined` and the caller keeps its state in memory only.
 */
export type IndexedDbRun = <T>(
  stores: string[],
  mode: IDBTransactionMode,
  work: (transaction: IDBTransaction) => IDBRequest<T> | void,
) => Promise<T | undefined>;

/** Opens the database lazily on first use; `upgrade` creates its object stores. */
export function indexedDb(name: string, version: number, upgrade: (db: IDBDatabase) => void): IndexedDbRun {
  let opening: Promise<IDBDatabase | null> | null = null;

  const database = (): Promise<IDBDatabase | null> => {
    opening ??= new Promise<IDBDatabase | null>((resolve) => {
      try {
        const request = indexedDB.open(name, version);
        request.onupgradeneeded = () => upgrade(request.result);
        request.onsuccess = () => resolve(request.result);
        request.onerror = () => resolve(null);
        request.onblocked = () => resolve(null);
      } catch {
        resolve(null);
      }
    });
    return opening;
  };

  return <T>(stores: string[], mode: IDBTransactionMode, work: (transaction: IDBTransaction) => IDBRequest<T> | void) =>
    database().then(
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
