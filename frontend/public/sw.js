// Aegis service worker. It receives Android shares (Web Share Target, see manifest.webmanifest): the shared files
// are kept in a local inbox and the app then uploads them through the normal authenticated API. It also shows push
// notifications (issue #28). It caches no app assets or data and handles no other request, so a deployment is never
// served stale code from here.
// Keep the inbox format in sync with src/share/inbox.ts and the push payload in sync with WebPushSender.java.

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

// A push carries {title, body, path, tag}, already in the member's language. The tag lets a repeated push replace
// the notification it repeats instead of showing it twice.
self.addEventListener("push", (event) => {
  let message = {};
  try {
    message = event.data ? event.data.json() : {};
  } catch {
    message = {};
  }
  const path = typeof message.path === "string" && message.path.startsWith("/") && !message.path.startsWith("//")
    ? message.path
    : "/inbox";
  event.waitUntil(
    self.registration.showNotification(message.title || "Aegis", {
      body: message.body || "",
      tag: message.tag || undefined,
      icon: "/icons/icon-192.png",
      data: { path },
    }),
  );
});

// A tap opens the notice's target in an open Aegis window, or in a new one.
self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const target = new URL(event.notification.data?.path || "/inbox", self.location.origin).href;
  event.waitUntil(
    (async () => {
      const windows = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
      for (const client of windows) {
        if (new URL(client.url).origin === self.location.origin && "focus" in client) {
          await client.focus();
          if ("navigate" in client) await client.navigate(target);
          return;
        }
      }
      await self.clients.openWindow(target);
    })(),
  );
});

async function receiveShare(request) {
  try {
    const contentType = request.headers.get("Content-Type") || "";
    // The body is read once as bytes: a shared file can be backed by the sending app's content URI, which may no
    // longer be readable later, and the raw bytes also allow the fallback parser below.
    const body = new Uint8Array(await request.arrayBuffer());
    let files = await formDataFiles(body, contentType);
    let source = "form";
    if (files.length === 0) {
      // Some Android apps produce parts that the FormData parser drops, e.g. a file part without a field name.
      files = multipartFiles(body, boundaryOf(contentType));
      source = "raw";
    }
    const shareId = self.crypto.randomUUID();
    const receivedAt = new Date().toISOString();
    const inbox = await caches.open(INBOX_CACHE);
    await Promise.all(
      files.map((file, index) =>
        inbox.put(
          new Request(new URL(`${INBOX_PREFIX}${shareId}/${index}`, self.location.origin).href),
          new Response(file.content, {
            headers: {
              "Content-Type": file.type || "application/octet-stream",
              "X-Aegis-Filename": encodeURIComponent(file.name || ""),
              "X-Aegis-Size": String(file.content.byteLength),
              "X-Aegis-Received-At": receivedAt,
            },
          }),
        ),
      ),
    );
    // 303 turns the share POST into a GET, so reloading or going back never re-submits the share.
    const target = new URL(`/share?received=${files.length}`, self.location.origin);
    if (files.length === 0) {
      target.searchParams.set("details", describeShare(contentType, body));
    } else if (source === "raw") {
      target.searchParams.set("parsed", "raw");
    }
    return Response.redirect(target.href, 303);
  } catch {
    return Response.redirect(new URL("/share?error=receive", self.location.origin).href, 303);
  }
}

/** File parts as the platform parses them: the ordinary path. */
async function formDataFiles(body, contentType) {
  if (!contentType) return [];
  try {
    const form = await new Response(body, { headers: { "Content-Type": contentType } }).formData();
    const parts = [...form.values()].filter((entry) => typeof entry !== "string");
    return Promise.all(
      parts.map(async (part) => ({ name: part.name, type: part.type, content: await part.arrayBuffer() })),
    );
  } catch {
    return [];
  }
}

function boundaryOf(contentType) {
  const match = /boundary=(?:"([^"]+)"|([^;]+))/i.exec(contentType);
  return match ? (match[1] ?? match[2]).trim() : "";
}

/** Fallback multipart reader: every part with a filename counts as a shared file, named or not. */
function multipartFiles(body, boundary) {
  if (!boundary) return [];
  const text = new TextDecoder("latin1").decode(body);
  const delimiter = `--${boundary}`;
  const files = [];
  let start = text.indexOf(delimiter);
  while (start !== -1) {
    const headerStart = start + delimiter.length;
    const headerEnd = text.indexOf("\r\n\r\n", headerStart);
    if (headerEnd === -1) break;
    const next = text.indexOf(`\r\n${delimiter}`, headerEnd);
    const contentEnd = next === -1 ? body.byteLength : next;
    const headers = text.substring(headerStart, headerEnd);
    const filename = /filename\*?=(?:"([^"]*)"|([^;\r\n]+))/i.exec(headers);
    if (filename) {
      files.push({
        name: decodeFilename((filename[1] ?? filename[2]).trim()),
        type: (/content-type:\s*([^;\r\n]+)/i.exec(headers)?.[1] ?? "").trim(),
        content: body.slice(headerEnd + 4, contentEnd),
      });
    }
    if (next === -1) break;
    start = next + 2;
  }
  return files;
}

/** Filenames arrive as UTF-8 bytes, which the latin1 view of the body shows one byte per character. */
function decodeFilename(raw) {
  const encoded = raw.startsWith("UTF-8''") ? decodeURIComponent(raw.substring(7)) : raw;
  try {
    return new TextDecoder().decode(Uint8Array.from(encoded, (character) => character.charCodeAt(0) & 0xff));
  } catch {
    return encoded;
  }
}

/** Shape of a share without files, shown on the share page for diagnosis: sizes and part headers, never content. */
function describeShare(contentType, body) {
  const boundary = boundaryOf(contentType);
  const headers = boundary
    ? new TextDecoder("latin1")
        .decode(body)
        .split(`--${boundary}`)
        .map((part) => part.split("\r\n\r\n")[0].replace(/\s+/g, " ").trim())
        .filter((part) => part !== "" && part !== "--")
    : [];
  return [`${(contentType || "none").split(";")[0]} ${body.byteLength}B`, `${headers.length} parts`, ...headers]
    .join("; ")
    .slice(0, 300);
}
