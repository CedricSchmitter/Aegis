import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { INBOX_CACHE, listSharedFiles, readSharedFile, type InboxCache } from "./inbox";

const ORIGIN = "https://aegis.test";

type Listener = (event: FetchLikeEvent) => void;
interface FetchLikeEvent {
  request: Request;
  respondWith(response: Promise<Response>): void;
}

/** Runs public/sw.js against an in-memory Cache API and returns its fetch handler and the inbox it writes. */
function loadServiceWorker() {
  const stores = new Map<string, Map<string, Response>>();
  const pathOf = (request: Request | string) =>
    new URL(typeof request === "string" ? request : request.url, ORIGIN).pathname;
  const open = async (name: string) => {
    const entries = stores.get(name) ?? new Map<string, Response>();
    stores.set(name, entries);
    return {
      put: async (request: Request, response: Response) => {
        // Like the Cache API, the stored body must be fully read before put resolves.
        entries.set(pathOf(request), new Response(await response.blob(), { headers: response.headers }));
      },
      keys: async () => [...entries.keys()].map((key) => new Request(ORIGIN + key)),
      match: async (request: Request | string) => entries.get(pathOf(request))?.clone(),
      delete: async (request: Request | string) => entries.delete(pathOf(request)),
    };
  };
  const listeners = new Map<string, Listener>();
  const self = {
    location: { origin: ORIGIN },
    crypto: globalThis.crypto,
    clients: { claim: async () => undefined },
    skipWaiting: () => undefined,
    addEventListener: (type: string, listener: Listener) => listeners.set(type, listener),
  };
  const source = readFileSync(path.resolve(import.meta.dirname, "../../public/sw.js"), "utf8");
  new Function("self", "caches", source)(self, { open });
  return { fetch: listeners.get("fetch")!, inbox: () => open(INBOX_CACHE) as Promise<InboxCache> };
}

function dispatch(fetch: Listener, request: Request): Promise<Response> | undefined {
  let answer: Promise<Response> | undefined;
  fetch({ request, respondWith: (response) => (answer = response) });
  return answer;
}

function share(files: File[]): Request {
  const form = new FormData();
  form.append("title", "ignored");
  files.forEach((file) => form.append("files", file));
  return new Request(`${ORIGIN}/share-target`, { method: "POST", body: form });
}

describe("service worker share target", () => {
  it("keeps every shared file in the inbox and redirects to the share page with a GET", async () => {
    const worker = loadServiceWorker();
    const files = [
      new File(["%PDF-1.7 one"], "Rechnung März.pdf", { type: "application/pdf" }),
      new File(["%PDF-1.7 two"], "Vertrag.pdf", { type: "application/pdf" }),
    ];

    const response = await dispatch(worker.fetch, share(files));

    expect(response?.status).toBe(303);
    expect(response?.headers.get("Location")).toBe(`${ORIGIN}/share?received=2`);
    const inbox = await worker.inbox();
    const listed = await listSharedFiles(inbox);
    expect(listed.map((file) => [file.name, file.type, file.size])).toEqual([
      ["Rechnung März.pdf", "application/pdf", 12],
      ["Vertrag.pdf", "application/pdf", 12],
    ]);
    expect(await (await readSharedFile(inbox, listed[1]))?.text()).toBe("%PDF-1.7 two");
  });

  it("accepts shared files under any field name", async () => {
    const worker = loadServiceWorker();
    const form = new FormData();
    form.append("file", new File(["%PDF x"], "x.pdf", { type: "application/pdf" }));

    const response = await dispatch(worker.fetch, new Request(`${ORIGIN}/share-target`, { method: "POST", body: form }));

    expect(response?.headers.get("Location")).toBe(`${ORIGIN}/share?received=1`);
    expect((await listSharedFiles(await worker.inbox())).map((file) => file.name)).toEqual(["x.pdf"]);
  });

  it("gives each share its own entries so a second share never replaces the first", async () => {
    const worker = loadServiceWorker();
    await dispatch(worker.fetch, share([new File(["%PDF a"], "a.pdf", { type: "application/pdf" })]));
    await dispatch(worker.fetch, share([new File(["%PDF b"], "b.pdf", { type: "application/pdf" })]));

    expect((await listSharedFiles(await worker.inbox())).map((file) => file.name).sort()).toEqual(["a.pdf", "b.pdf"]);
  });

  it("reports a share without files and leaves every other request to the network", async () => {
    const worker = loadServiceWorker();

    const empty = await dispatch(worker.fetch, share([]));
    const location = new URL(empty!.headers.get("Location")!);
    expect(location.pathname + "?received=" + location.searchParams.get("received")).toBe("/share?received=0");
    expect(location.searchParams.get("details")).toBe("multipart/form-data; title=text(7)");

    expect(dispatch(worker.fetch, new Request(`${ORIGIN}/share-target`))).toBeUndefined();
    expect(dispatch(worker.fetch, new Request(`${ORIGIN}/api/documents`, { method: "POST", body: "x" }))).toBeUndefined();
    expect(dispatch(worker.fetch, new Request(`${ORIGIN}/today`))).toBeUndefined();
  });

  it("redirects to an explicit error when the share cannot be read", async () => {
    const worker = loadServiceWorker();
    const broken = new Request(`${ORIGIN}/share-target`, {
      method: "POST",
      body: "not multipart",
      headers: { "Content-Type": "multipart/form-data; boundary=missing" },
    });

    const response = await dispatch(worker.fetch, broken);

    expect(response?.headers.get("Location")).toBe(`${ORIGIN}/share?error=receive`);
  });
});
