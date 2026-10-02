import { describe, expect, it } from "vitest";
import {
  addDocument,
  addScanPages,
  detailsOf,
  emptyPlan,
  freshOperation,
  joinWithPrevious,
  keepScanPages,
  LIMITS,
  movePage,
  movePageTo,
  planProblems,
  prepareSend,
  removePage,
  rotatePage,
  setDetails,
  setTitle,
  splitBefore,
  titleOf,
  toRequest,
  type PagePlan,
  type SourceInfo,
  type TitleWords,
} from "./plan";

const words: TitleWords = { newPages: "Neue Seiten", part: (title, number) => `${title}, Teil ${number}` };

const source = (documentId: string, title: string, pages: number, extra: Partial<SourceInfo> = {}): SourceInfo => ({
  documentId,
  title,
  categoryId: null,
  subjects: [],
  sourceKind: "PDF",
  pages: Array.from({ length: pages }, () => ({ width: 595, height: 842 })),
  warnings: [],
  ...extra,
});

let counter = 0;
const ids = () => `op-${++counter}`;
const plan = (...sources: SourceInfo[]): PagePlan =>
  sources.reduce((current, entry) => addDocument(current, entry), emptyPlan("lisa", ids));

/** Each output as "doc:page" references (one-based) or "scan:id", for readable expectations. */
const layout = (current: PagePlan) =>
  current.outputs.map((output) =>
    output.pages.map((page) => (page.source === "DOCUMENT" ? `${page.documentId}:${page.pageIndex + 1}` : `scan:${page.scanPageId}`)),
  );
const keyOf = (current: PagePlan, output: number, position: number) => current.outputs[output].pages[position].key;

describe("page editor plan", () => {
  it("splits a document with a marker and joins it again", () => {
    const strom = plan(source("strom", "Stromrechnung", 4));
    const split = splitBefore(strom, strom.outputs[0].key, 2);
    expect(layout(split)).toEqual([["strom:1", "strom:2"], ["strom:3", "strom:4"]]);

    const joined = joinWithPrevious(split, split.outputs[1].key);
    expect(layout(joined)).toEqual([["strom:1", "strom:2", "strom:3", "strom:4"]]);
    // The previous document keeps its identity, so a title typed there survives.
    expect(joined.outputs[0].key).toBe(strom.outputs[0].key);
  });

  it("merges by appending another document's pages to the last new document", () => {
    const merged = plan(source("vertrag", "Vertrag", 2), source("anlage", "Anlage", 1));
    expect(layout(merged)).toEqual([["vertrag:1", "vertrag:2", "anlage:1"]]);
  });

  it("moves a page over a marker into the neighbouring document and drops a document left empty", () => {
    const base = plan(source("a", "A", 3));
    const split = splitBefore(base, base.outputs[0].key, 1);
    expect(layout(split)).toEqual([["a:1"], ["a:2", "a:3"]]);

    const earlier = movePage(split, keyOf(split, 1, 0), -1);
    expect(layout(earlier)).toEqual([["a:1", "a:2"], ["a:3"]]);

    const later = movePage(split, keyOf(split, 0, 0), 1);
    expect(layout(later)).toEqual([["a:1", "a:2", "a:3"]]);
    // The remaining document is the second one, with its own key.
    expect(later.outputs[0].key).toBe(split.outputs[1].key);

    expect(movePage(split, keyOf(split, 0, 0), -1)).toBe(split);
  });

  it("drops a dragged page at the target position, also in another document", () => {
    const base = plan(source("a", "A", 4));
    const split = splitBefore(base, base.outputs[0].key, 2);
    const first = split.outputs[0].key;
    const second = split.outputs[1].key;

    expect(layout(movePageTo(split, keyOf(split, 0, 0), first, 2))).toEqual([["a:2", "a:1"], ["a:3", "a:4"]]);
    expect(layout(movePageTo(split, keyOf(split, 1, 1), first, 0))).toEqual([["a:4", "a:1", "a:2"], ["a:3"]]);
    expect(layout(movePageTo(split, keyOf(split, 0, 1), second, 2))).toEqual([["a:1"], ["a:3", "a:4", "a:2"]]);
    expect(movePageTo(split, keyOf(split, 0, 1), first, 1)).toBe(split);
  });

  it("rotates in quarter turns and leaves out removed pages", () => {
    const base = plan(source("a", "A", 2));
    const key = keyOf(base, 0, 0);
    const turned = rotatePage(rotatePage(rotatePage(rotatePage(rotatePage(base, key), key), key), key), key);
    expect(turned.outputs[0].pages[0].rotation).toBe(90);

    const removed = removePage(removePage(base, key), keyOf(base, 0, 1));
    expect(removed.outputs).toEqual([]);
  });

  it("prefills titles from the first page's source and numbers parts of the same source", () => {
    const base = plan(source("strom", "Stromrechnung", 3));
    const split = splitBefore(base, base.outputs[0].key, 1);
    expect([titleOf(split, 0, words), titleOf(split, 1, words)]).toEqual(["Stromrechnung, Teil 1", "Stromrechnung, Teil 2"]);
    expect(titleOf(base, 0, words)).toBe("Stromrechnung");

    const withScan = addScanPages(emptyPlan("lisa"), ["p1"]);
    expect(titleOf(withScan, 0, words)).toBe("Neue Seiten");

    const chosen = setTitle(split, split.outputs[1].key, "Mahnung");
    expect([titleOf(chosen, 0, words), titleOf(chosen, 1, words)]).toEqual(["Stromrechnung, Teil 1", "Mahnung"]);
    expect(toRequest(chosen, words).outputs.map((output) => output.titleChosen)).toEqual([false, true]);
  });

  it("takes folder and subjects from the first page's source until the member chooses them", () => {
    const base = plan(
      source("vertrag", "Vertrag", 1, { categoryId: "folder-1", subjects: ["HOUSE", "MEMBER:lisa"] }),
      source("anlage", "Anlage", 1, { categoryId: "folder-2" }),
    );
    expect(detailsOf(base, base.outputs[0])).toEqual({ categoryId: "folder-1", subjects: ["HOUSE", "MEMBER:lisa"] });

    const moved = movePage(base, keyOf(base, 0, 1), -1);
    expect(detailsOf(moved, moved.outputs[0]).categoryId).toBe("folder-2");

    const chosen = setDetails(moved, moved.outputs[0].key, { categoryId: null, subjects: ["MEMBER:cedric"] });
    const request = toRequest(chosen, words);
    expect(request.outputs[0].categoryId).toBeNull();
    expect(request.outputs[0].subjects).toEqual([{ kind: "MEMBER", memberId: "cedric" }]);
  });

  it("numbers new pages by their first appearance, which is their order in the attached PDF", () => {
    const base = addScanPages(plan(source("a", "A", 1)), ["p1", "p2"]);
    const reordered = movePageTo(base, keyOf(base, 0, 2), base.outputs[0].key, 0);
    const request = toRequest(rotatePage(reordered, keyOf(reordered, 0, 0)), words);
    expect(request.outputs[0].pages).toEqual([
      { source: "SCAN", documentId: null, pageIndex: 0, rotation: 90 },
      { source: "DOCUMENT", documentId: "a", pageIndex: 0, rotation: 0 },
      { source: "SCAN", documentId: null, pageIndex: 1, rotation: 0 },
    ]);
  });

  it("drops references to new pages whose photo is gone", () => {
    const base = addScanPages(emptyPlan("lisa"), ["p1", "p2"]);
    expect(layout(keepScanPages(base, ["p2"]))).toEqual([["scan:p2"]]);
    expect(keepScanPages(base, ["p1", "p2"])).toBe(base);
  });

  it("reports limits and missing titles before sending", () => {
    expect(planProblems(emptyPlan("lisa"), words)).toEqual(["pageEdit.plan.empty"]);

    const many = plan(...Array.from({ length: LIMITS.sources + 1 }, (_, index) => source(`d${index}`, `D${index}`, 1)));
    expect(planProblems(many, words)).toEqual(["pageEdit.limit.sources"]);

    const photos = addScanPages(emptyPlan("lisa"), Array.from({ length: LIMITS.newPages + 1 }, (_, index) => `p${index}`));
    expect(planProblems(photos, words)).toEqual(["pageEdit.limit.newPages"]);

    const blank = plan(source("a", "A", 1));
    expect(planProblems(setTitle(blank, blank.outputs[0].key, "  "), words)).toEqual(["document.title.missing"]);
  });

  it("keeps the operation id for an unchanged retry and takes a new one for changed content", () => {
    const base = plan(source("a", "A", 2));
    const first = prepareSend(base, words, "[]", ids);
    expect(first.request.id).toBe(base.operationId);

    // A retry after a lost answer sends the same request under the same id.
    const retry = prepareSend(first.plan, words, "[]", ids);
    expect(retry.request).toEqual(first.request);

    const changed = prepareSend(rotatePage(first.plan, keyOf(first.plan, 0, 0)), words, "[]", ids);
    expect(changed.request.id).not.toBe(base.operationId);

    // New corners on a photographed page change the attachment, so they count as changed content too.
    expect(prepareSend(first.plan, words, '[{"corners":1}]', ids).request.id).not.toBe(base.operationId);

    const fresh = freshOperation({ ...first.plan, pending: true }, ids);
    expect(fresh.operationId).not.toBe(base.operationId);
    expect(fresh.pending).toBe(false);
    expect(fresh.sentFingerprint).toBeNull();
  });
});
