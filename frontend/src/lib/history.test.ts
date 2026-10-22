import { describe, expect, it } from "vitest";
import { appendPage, labelDelta, labelIds, olderThanRecording, type HistoryEntry } from "./history";

const entry = (id: string): HistoryEntry => ({
  id,
  occurredAt: "2026-10-22T10:00:00Z",
  actorKind: "MEMBER",
  action: "EDITED",
  changes: [],
  undoable: false,
});

describe("labelDelta", () => {
  it("names the labels a change added and removed", () => {
    expect(labelDelta({ field: "labels", before: "a,b", after: "b,c" })).toEqual({ added: ["c"], removed: ["a"] });
  });

  it("treats a missing value as no labels", () => {
    expect(labelIds(null)).toEqual([]);
    expect(labelDelta({ field: "labels", before: null, after: "a" })).toEqual({ added: ["a"], removed: [] });
    expect(labelDelta({ field: "labels", before: "a", after: null })).toEqual({ added: [], removed: ["a"] });
  });
});

describe("olderThanRecording", () => {
  it("says a record created before recording began has unrecorded changes", () => {
    expect(olderThanRecording("2026-09-01T08:00:00Z", "2026-10-22T09:00:00Z")).toBe(true);
    expect(olderThanRecording("2026-10-22T09:30:00Z", "2026-10-22T09:00:00Z")).toBe(false);
  });
});

describe("appendPage", () => {
  it("adds the next page without repeating entries already shown", () => {
    expect(appendPage([entry("a"), entry("b")], [entry("b"), entry("c")]).map((e) => e.id)).toEqual(["a", "b", "c"]);
  });
});
