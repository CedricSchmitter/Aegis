/**
 * Local inbox of files shared to Aegis from other Android apps. The service worker (public/sw.js) writes one Cache
 * API entry per shared file; the share page reads them here and removes each one only after the server confirmed
 * durable storage. Keep the format in sync with public/sw.js.
 */
export const INBOX_CACHE = "aegis-share-inbox-v1";
export const INBOX_PREFIX = "/__share-inbox/";

/** Shared files that were never saved are dropped after this age; the original stays in the source app. */
export const INBOX_RETENTION_MS = 7 * 24 * 60 * 60 * 1000;

/** The part of the Cache API the inbox uses, so tests can provide an in-memory implementation. */
export interface InboxCache {
  keys(): Promise<readonly Request[]>;
  match(request: Request | string): Promise<Response | undefined>;
  delete(request: Request | string): Promise<boolean>;
}

export interface SharedFile {
  /** Stable within the inbox: `<shareId>/<index>`. */
  key: string;
  name: string;
  type: string;
  size: number;
  receivedAt: string;
}

export async function openInbox(): Promise<InboxCache | null> {
  if (typeof caches === "undefined") return null;
  try {
    return await caches.open(INBOX_CACHE);
  } catch {
    return null;
  }
}

function keyOf(request: Request): string | null {
  const path = new URL(request.url, "https://aegis.invalid").pathname;
  return path.startsWith(INBOX_PREFIX) ? path.substring(INBOX_PREFIX.length) : null;
}

function describe(key: string, response: Response): SharedFile {
  const headers = response.headers;
  let name = "";
  try {
    name = decodeURIComponent(headers.get("X-Aegis-Filename") ?? "");
  } catch {
    // keep the empty name; the page shows a placeholder and upload assigns a fallback name
  }
  return {
    key,
    name,
    type: headers.get("Content-Type") ?? "",
    size: Number(headers.get("X-Aegis-Size") ?? "0") || 0,
    receivedAt: headers.get("X-Aegis-Received-At") ?? "",
  };
}

/** Pending shared files, oldest share first, in the order they were shared. Expired entries are removed. */
export async function listSharedFiles(inbox: InboxCache, now: Date = new Date()): Promise<SharedFile[]> {
  const files: SharedFile[] = [];
  for (const request of await inbox.keys()) {
    const key = keyOf(request);
    if (!key) continue;
    const response = await inbox.match(request);
    if (!response) continue;
    const file = describe(key, response);
    const received = Date.parse(file.receivedAt);
    if (Number.isNaN(received) || now.getTime() - received > INBOX_RETENTION_MS) {
      await inbox.delete(request);
      continue;
    }
    files.push(file);
  }
  return files.sort((a, b) => a.receivedAt.localeCompare(b.receivedAt) || compareKeys(a.key, b.key));
}

function compareKeys(a: string, b: string): number {
  const [shareA, indexA] = a.split("/");
  const [shareB, indexB] = b.split("/");
  return shareA.localeCompare(shareB) || Number(indexA) - Number(indexB);
}

/** The shared content as a File, or null when it was removed in the meantime (e.g. saved from another tab). */
export async function readSharedFile(inbox: InboxCache, file: SharedFile): Promise<File | null> {
  const response = await inbox.match(INBOX_PREFIX + file.key);
  if (!response) return null;
  const blob = await response.blob();
  return new File([blob], file.name, { type: file.type });
}

export async function removeSharedFile(inbox: InboxCache, file: SharedFile): Promise<void> {
  await inbox.delete(INBOX_PREFIX + file.key);
}
