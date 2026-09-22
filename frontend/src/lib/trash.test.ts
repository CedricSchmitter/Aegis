import { describe, expect, it } from "vitest";
import type { ContractSummaryDto, DocumentDto, TaskDto } from "../api/types";
import { countTrash, filterTrash, mergeTrash } from "./trash";

const trash = (at: string) => ({ trashedBy: "m1", trashedAt: at, restorableUntil: "2026-10-22T10:00:00Z" });

const document = (id: string, title: string, at: string | null) =>
  ({ id, title, version: 3, trash: at ? trash(at) : null }) as unknown as DocumentDto;
const task = (id: string, title: string, at: string | null) =>
  ({ id, title, version: 1, trash: at ? trash(at) : null }) as unknown as TaskDto;
const contract = (id: string, name: string, at: string | null) =>
  ({ id, name, version: 7, trash: at ? trash(at) : null }) as unknown as ContractSummaryDto;

describe("mergeTrash", () => {
  it("lists every kind most recently trashed first with a link to its page", () => {
    const entries = mergeTrash(
      [document("d1", "Scan", "2026-09-20T08:00:00Z")],
      [task("t1", "Doppelt", "2026-09-22T09:00:00Z")],
      [contract("c1", "Fitnessstudio", "2026-09-21T09:00:00Z")],
    );

    expect(entries.map((entry) => [entry.kind, entry.id, entry.link, entry.version])).toEqual([
      ["task", "t1", "/tasks/t1", 1],
      ["contract", "c1", "/contracts/c1", 7],
      ["document", "d1", "/documents/d1", 3],
    ]);
    expect(entries[1].title).toBe("Fitnessstudio");
  });

  it("ignores records that are no longer in the trash", () => {
    const entries = mergeTrash([document("d1", "Restored", null)], [task("t1", "Trashed", "2026-09-22T09:00:00Z")], []);

    expect(entries.map((entry) => entry.id)).toEqual(["t1"]);
  });
});

describe("filterTrash and countTrash", () => {
  const entries = mergeTrash(
    [document("d1", "A", "2026-09-20T08:00:00Z"), document("d2", "B", "2026-09-20T09:00:00Z")],
    [task("t1", "C", "2026-09-22T09:00:00Z")],
    [],
  );

  it("filters by kind and keeps everything for all", () => {
    expect(filterTrash(entries, "document").map((entry) => entry.id)).toEqual(["d2", "d1"]);
    expect(filterTrash(entries, "contract")).toEqual([]);
    expect(filterTrash(entries, "all")).toHaveLength(3);
  });

  it("counts entries per kind", () => {
    expect(countTrash(entries)).toEqual({ all: 3, document: 2, task: 1, contract: 0 });
  });
});
