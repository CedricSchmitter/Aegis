// Aegis service worker. It only receives Android shares (Web Share Target, see manifest.webmanifest): the shared
// files are kept in a local inbox and the app then uploads them through the normal authenticated API. It caches no
// app assets or data and handles no other request, so a deployment is never served stale code from here.
// Keep the inbox format in sync with src/share/inbox.ts.

const INBOX_CACHE = "aegis-share-inbox-v1";
const INBOX_PREFIX = "/__share-inbox/";

self.addEventListener("install", () => {
  // Nothing is cached, so a new version can take over immediately instead of waiting for all tabs to close.
  self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(self.clients.claim());
});

self.addEventListener("fetch", (event) => {
  const url = new URL(event.request.url);
  if (event.request.method === "POST" && url.origin === self.location.origin && url.pathname === "/share-target") {
    event.respondWith(receiveShare(event.request));
  }
});

async function receiveShare(request) {
  try {
    const form = await request.formData();
    // The manifest names the field "files", but any file part is accepted: what Chrome sends differs by app.
    const files = [...form.values()].filter((entry) => typeof entry !== "string");
    const shareId = self.crypto.randomUUID();
    const receivedAt = new Date().toISOString();
    const inbox = await caches.open(INBOX_CACHE);
    // Read the bytes now: the shared file can be backed by the sending app's content URI, which may no longer be
    // readable once the share has been handed over.
    const contents = await Promise.all(files.map((file) => file.arrayBuffer()));
    await Promise.all(
      files.map((file, index) =>
        inbox.put(
          new Request(new URL(`${INBOX_PREFIX}${shareId}/${index}`, self.location.origin).href),
          new Response(contents[index], {
            headers: {
              "Content-Type": file.type || "application/octet-stream",
              "X-Aegis-Filename": encodeURIComponent(file.name || ""),
              "X-Aegis-Size": String(contents[index].byteLength),
              "X-Aegis-Received-At": receivedAt,
            },
          }),
        ),
      ),
    );
    // 303 turns the share POST into a GET, so reloading or going back never re-submits the share.
    const target = new URL(`/share?received=${files.length}`, self.location.origin);
    if (files.length === 0) target.searchParams.set("details", describeForm(request, form));
    return Response.redirect(target.href, 303);
  } catch {
    return Response.redirect(new URL("/share?error=receive", self.location.origin).href, 303);
  }
}

// Shape of a share without files, shown on the share page for diagnosis: field names and sizes, never content.
function describeForm(request, form) {
  const type = (request.headers.get("Content-Type") || "none").split(";")[0];
  const fields = [...form.entries()].map(([name, value]) =>
    typeof value === "string" ? `${name}=text(${value.length})` : `${name}=file(${value.type || "?"},${value.size})`,
  );
  return [type, ...fields].join("; ").slice(0, 300);
}
