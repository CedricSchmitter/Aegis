import type { CategoryDto } from "../api/types";

export interface FolderNode {
  category: CategoryDto;
  depth: number;
  children: FolderNode[];
}

/** Builds the folder hierarchy; the backend lists folders depth first, but the order is not relied upon. */
export function buildTree(categories: CategoryDto[]): FolderNode[] {
  const nodes = new Map<string, FolderNode>();
  for (const category of categories) nodes.set(category.id, { category, depth: 0, children: [] });
  const roots: FolderNode[] = [];
  for (const node of nodes.values()) {
    const parent = node.category.parentId ? nodes.get(node.category.parentId) : undefined;
    if (parent) parent.children.push(node);
    else roots.push(node);
  }
  const byName = (a: FolderNode, b: FolderNode) =>
    a.category.name.localeCompare(b.category.name, undefined, { sensitivity: "base" });
  const setDepth = (node: FolderNode, depth: number) => {
    node.depth = depth;
    node.children.sort(byName);
    node.children.forEach((child) => setDepth(child, depth + 1));
  };
  roots.sort(byName);
  roots.forEach((root) => setDepth(root, 0));
  return roots;
}

/** Depth-first list of all folders, parents before children. */
export function flatten(nodes: FolderNode[]): FolderNode[] {
  return nodes.flatMap((node) => [node, ...flatten(node.children)]);
}

export function findNode(nodes: FolderNode[], id: string | null): FolderNode | null {
  if (!id) return null;
  return flatten(nodes).find((node) => node.category.id === id) ?? null;
}

/** Folders from the top level down to the given folder. */
export function pathOf(categories: CategoryDto[], id: string | null): CategoryDto[] {
  const byId = new Map(categories.map((category) => [category.id, category]));
  const path: CategoryDto[] = [];
  let current = id ? byId.get(id) : undefined;
  while (current) {
    path.unshift(current);
    current = current.parentId ? byId.get(current.parentId) : undefined;
  }
  return path;
}

export function subtreeIds(nodes: FolderNode[], id: string): string[] {
  const node = findNode(nodes, id);
  return node ? flatten([node]).map((entry) => entry.category.id) : [];
}

/** Whether candidate lies inside the subtree of ancestor (a folder cannot move into itself). */
export function isInSubtree(nodes: FolderNode[], candidate: string, ancestor: string): boolean {
  return subtreeIds(nodes, ancestor).includes(candidate);
}

/** Documents in the folder and all its subfolders. */
export function subtreeCount(node: FolderNode): number {
  return flatten([node]).reduce((sum, entry) => sum + entry.category.documentCount, 0);
}

/** Ids of the folders that need to be expanded to reveal the selected one. */
export function ancestorsOf(categories: CategoryDto[], id: string | null): string[] {
  return pathOf(categories, id)
    .slice(0, -1)
    .map((category) => category.id);
}

/**
 * The folders the phone's folder rail offers for the selected folder: the top level when none (or Unsorted) is
 * chosen, the subfolders of a folder that has some, and otherwise the folder's siblings, so the chosen folder stays
 * visible among them.
 */
export function railFolders(categories: CategoryDto[], selected: string | null): FolderNode[] {
  const roots = buildTree(categories);
  const node = findNode(roots, selected);
  if (!node) return roots;
  if (node.children.length > 0) return node.children;
  const parent = findNode(roots, node.category.parentId ?? null);
  return parent ? parent.children : roots;
}
