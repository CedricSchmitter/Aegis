import type { ContractSummaryDto, DocumentDto, TaskDto } from "../api/types";

/** The three kinds of household records that can be moved to the trash (issue #48). */
export const TRASH_KINDS = ["document", "task", "contract"] as const;
export type TrashKind = (typeof TRASH_KINDS)[number];

/** Who moved a record to the trash, when, and until when it is guaranteed to stay restorable. */
export interface TrashInfo {
  trashedBy: string;
  trashedAt: string;
  restorableUntil: string;
}

/** One row of the trash view, whatever kind of record it is. */
export interface TrashEntry {
  kind: TrashKind;
  id: string;
  title: string;
  version: number;
  trash: TrashInfo;
  link: string;
}

/**
 * Combines the three trash listings into one list, most recently trashed first. Records without a trash entry are
 * ignored, so a listing that raced with a restore cannot show a record as trashed.
 */
export function mergeTrash(
  documents: DocumentDto[],
  tasks: TaskDto[],
  contracts: ContractSummaryDto[],
): TrashEntry[] {
  const entries: TrashEntry[] = [];
  for (const document of documents) {
    if (document.trash) {
      entries.push({
        kind: "document",
        id: document.id,
        title: document.title,
        version: document.version,
        trash: document.trash,
        link: `/documents/${document.id}`,
      });
    }
  }
  for (const task of tasks) {
    if (task.trash) {
      entries.push({ kind: "task", id: task.id, title: task.title, version: task.version, trash: task.trash, link: `/tasks/${task.id}` });
    }
  }
  for (const contract of contracts) {
    if (contract.trash) {
      entries.push({
        kind: "contract",
        id: contract.id,
        title: contract.name,
        version: contract.version,
        trash: contract.trash,
        link: `/contracts/${contract.id}`,
      });
    }
  }
  return entries.sort((a, b) => b.trash.trashedAt.localeCompare(a.trash.trashedAt) || a.title.localeCompare(b.title));
}

/** Entries of one kind, or all of them. */
export function filterTrash(entries: TrashEntry[], kind: TrashKind | "all"): TrashEntry[] {
  return kind === "all" ? entries : entries.filter((entry) => entry.kind === kind);
}

/** Number of entries per kind, for the filter chips. */
export function countTrash(entries: TrashEntry[]): Record<TrashKind | "all", number> {
  const counts: Record<TrashKind | "all", number> = { all: entries.length, document: 0, task: 0, contract: 0 };
  for (const entry of entries) counts[entry.kind] += 1;
  return counts;
}
