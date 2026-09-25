import "fake-indexeddb/auto";
import { describe, expect, it } from "vitest";
import { AUTOMATIC, type ScanDraft, type ScanPage } from "./draft";
import { clearDraft, deletePage, loadDraft, saveDraft } from "./draftStore";

const page = (id: string): ScanPage => ({ id, photo: new Blob([`photo ${id}`]), settings: AUTOMATIC, result: null });

describe("scan draft storage", () => {
  it("restores a member's pages in order, with their photos, after a reload", async () => {
    const a = page("a");
    const b = page("b");
    const draft: ScanDraft = { memberId: "lisa", name: "Scan 2026-09-25 10.15", pages: [a, b] };
    await saveDraft(draft, [a, b]);
    // A reorder only stores the new order; the photos are not written again.
    await saveDraft({ ...draft, pages: [b, a] }, []);

    const restored = await loadDraft("lisa");
    expect(restored?.name).toBe("Scan 2026-09-25 10.15");
    expect(restored?.pages.map((p) => p.id)).toEqual(["b", "a"]);
    expect(await restored!.pages[1].photo.text()).toBe("photo a");
    expect(await loadDraft("cedric")).toBeNull();
  });

  it("forgets removed pages and the whole scan once it is saved or discarded", async () => {
    const c = page("c");
    const d = page("d");
    const other = page("e");
    await saveDraft({ memberId: "cedric", name: "Scan", pages: [c, d] }, [c, d]);
    await saveDraft({ memberId: "lisa", name: "Scan", pages: [other] }, [other]);

    await deletePage("c");
    await saveDraft({ memberId: "cedric", name: "Scan", pages: [d] }, []);
    expect((await loadDraft("cedric"))?.pages.map((p) => p.id)).toEqual(["d"]);

    await clearDraft("cedric");
    expect(await loadDraft("cedric")).toBeNull();
    // Another member's scan on the same phone is untouched.
    expect((await loadDraft("lisa"))?.pages.map((p) => p.id)).toEqual(["e"]);
  });
});
