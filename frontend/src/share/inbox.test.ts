import { describe, expect, it } from "vitest";
import {
  INBOX_PREFIX,
  INBOX_RETENTION_MS,
  listSharedFiles,
  readSharedFile,
  removeSharedFile,
  type InboxCache,
} from "./inbox";

const ORIGIN = "https://aegis.test";

/** In-memory stand-in for the Cache API, storing entries the way public/sw.js does. */
class MemoryInbox implements InboxCache {
  readonly entries = new Map<string, Response>();

  put(path: string, content: string, headers: Record<string, string>) {
    this.entries.set(path, new Response(content, { headers }));
  }

  share(shareId: string, index: number, name: string, receivedAt: string, content = "%PDF-1.7 test") {
    this.put(`${INBOX_PREFIX}${shareId}/${index}`, content, {
      "Content-Type": "application/pdf",
      "X-Aegis-Filename": encodeURIComponent(name),
      "X-Aegis-Size": String(content.length),
      "X-Aegis-Received-At": receivedAt,
    });
  }

  private path(request: Request | string): string {
    return new URL(typeof request === "string" ? request : request.url, ORIGIN).pathname;
  }

  async keys() {
    return [...this.entries.keys()].map((path) => new Request(ORIGIN + path));
  }

  async match(request: Request | string) {
    return this.entries.get(this.path(request))?.clone();
  }

  async delete(request: Request | string) {
    return this.entries.delete(this.path(request));
  }
}

const NOW = new Date("2026-09-19T10:00:00Z");

describe("share inbox", () => {
  it("lists shared files by share time and position, with decoded names", async () => {
    const inbox = new MemoryInbox();
    inbox.share("b", 1, "Rechnung März.pdf", "2026-09-19T09:00:00Z");
    inbox.share("b", 0, "Vertrag.pdf", "2026-09-19T09:00:00Z");
    inbox.share("a", 0, "älter.pdf", "2026-09-18T09:00:00Z");

    const files = await listSharedFiles(inbox, NOW);

    expect(files.map((file) => file.name)).toEqual(["älter.pdf", "Vertrag.pdf", "Rechnung März.pdf"]);
    expect(files[1]).toMatchObject({ key: "b/0", type: "application/pdf", size: 13 });
  });

  it("drops expired and unreadable entries and ignores unrelated cache entries", async () => {
    const inbox = new MemoryInbox();
    inbox.share("old", 0, "old.pdf", new Date(NOW.getTime() - INBOX_RETENTION_MS - 1).toISOString());
    inbox.share("broken", 0, "broken.pdf", "not a date");
    inbox.share("fresh", 0, "fresh.pdf", "2026-09-19T09:59:00Z");
    inbox.put("/somewhere-else", "x", {});

    const files = await listSharedFiles(inbox, NOW);

    expect(files.map((file) => file.key)).toEqual(["fresh/0"]);
    expect([...inbox.entries.keys()].sort()).toEqual([`${INBOX_PREFIX}fresh/0`, "/somewhere-else"]);
  });

  it("returns the content as a File and removes a file individually", async () => {
    const inbox = new MemoryInbox();
    inbox.share("s", 0, "one.pdf", "2026-09-19T09:00:00Z", "%PDF-one");
    inbox.share("s", 1, "two.pdf", "2026-09-19T09:00:00Z", "%PDF-two");
    const [one, two] = await listSharedFiles(inbox, NOW);

    const content = await readSharedFile(inbox, one);
    expect(content?.name).toBe("one.pdf");
    expect(await content?.text()).toBe("%PDF-one");

    await removeSharedFile(inbox, one);
    expect(await readSharedFile(inbox, one)).toBeNull();
    expect((await listSharedFiles(inbox, NOW)).map((file) => file.key)).toEqual([two.key]);
  });
});
