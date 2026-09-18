import { describe, expect, it } from "vitest";
import type { CategoryDto } from "../api/types";
import { ancestorsOf, buildTree, findNode, flatten, isInSubtree, pathOf, subtreeCount, subtreeIds } from "./tree";

function folder(id: string, parentId: string | null, name: string, documentCount = 0): CategoryDto {
  return {
    id,
    parentId,
    name,
    impliedLabelIds: [],
    impliedSubjects: [],
    documentCount,
    version: 1,
  };
}

const categories: CategoryDto[] = [
  folder("internet", "invoices", "Internet", 3),
  folder("house", null, "Haus", 1),
  folder("invoices", "house", "Rechnungen", 2),
  folder("water", "invoices", "Wasser", 4),
  folder("car", null, "Auto"),
];

describe("folder tree", () => {
  it("nests folders and sorts siblings by name", () => {
    const roots = buildTree(categories);

    expect(roots.map((node) => node.category.name)).toEqual(["Auto", "Haus"]);
    expect(flatten(roots).map((node) => node.category.name)).toEqual([
      "Auto",
      "Haus",
      "Rechnungen",
      "Internet",
      "Wasser",
    ]);
    expect(flatten(roots).map((node) => node.depth)).toEqual([0, 0, 1, 2, 2]);
  });

  it("answers paths, subtrees and counts", () => {
    const roots = buildTree(categories);

    expect(pathOf(categories, "internet").map((category) => category.name)).toEqual([
      "Haus",
      "Rechnungen",
      "Internet",
    ]);
    expect(pathOf(categories, null)).toEqual([]);
    expect(subtreeIds(roots, "invoices").sort()).toEqual(["internet", "invoices", "water"]);
    expect(subtreeCount(findNode(roots, "house")!)).toBe(10);
    expect(subtreeCount(findNode(roots, "water")!)).toBe(4);
    expect(ancestorsOf(categories, "internet")).toEqual(["house", "invoices"]);
  });

  it("recognizes a folder inside its own subtree so moves cannot create a cycle", () => {
    const roots = buildTree(categories);

    expect(isInSubtree(roots, "internet", "house")).toBe(true);
    expect(isInSubtree(roots, "house", "internet")).toBe(false);
    expect(isInSubtree(roots, "house", "house")).toBe(true);
    expect(findNode(roots, "missing")).toBeNull();
  });
});
