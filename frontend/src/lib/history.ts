/**
 * The change history of a document or a task (ADR-0030). Both share this shape; values are ids, ISO dates, enum
 * names or the member's own text, and the page turns them into words.
 */
export interface HistoryFieldChange {
  field: string;
  before?: string | null;
  after?: string | null;
}

export interface HistoryEntry {
  id: string;
  occurredAt: string;
  actorKind: string;
  actorMemberId?: string | null;
  actorReference?: string | null;
  action: string;
  changes: HistoryFieldChange[];
  undoOf?: string | null;
  undoneBy?: string | null;
  undoable: boolean;
}

/** One page, newest first; {@code next} continues it and is null on the last page. */
export interface HistoryPage {
  recordingSince: string;
  changes: HistoryEntry[];
  next: number | null;
}

/** Actions of task and document entries; each has its own words in both catalogs. */
export const HISTORY_ACTIONS = [
  "CREATED", "EDITED", "ASSIGNED", "PLANNED", "DUE_DATE_CHANGED", "COMPLETED", "REOPENED", "TRASHED", "RESTORED",
  "IMPORTED", "UNDONE", "CONTRACT_UPDATED", "SUPERSEDED", "REVIVED", "CONTRACT_DELETED", "RENAMED", "FILENAME_CHANGED",
  "MOVED", "LABELS_CHANGED", "ANALYZED",
] as const;

/** Recorded fields of tasks and documents. */
export const HISTORY_FIELDS = ["title", "note", "owner", "status", "dueDate", "plannedDate", "filename", "folder", "labels"] as const;

/** Automations that appear as authors. */
export const HISTORY_AUTOMATIONS = ["contractDeadlines"] as const;

/** A stored label list: sorted label ids, comma-separated; nothing means no labels. */
export function labelIds(value: string | null | undefined): string[] {
  return value ? value.split(",") : [];
}

/** Which labels a change added and which it removed. */
export function labelDelta(change: HistoryFieldChange): { added: string[]; removed: string[] } {
  const before = labelIds(change.before);
  const after = labelIds(change.after);
  return {
    added: after.filter((id) => !before.includes(id)),
    removed: before.filter((id) => !after.includes(id)),
  };
}

/**
 * Whether changes before recording began are missing for a record: it existed before the history was switched on,
 * so its earliest entries were never recorded.
 */
export function olderThanRecording(createdAt: string, recordingSince: string): boolean {
  return new Date(createdAt).getTime() < new Date(recordingSince).getTime();
}

/** Appends a further page, ignoring entries that are already shown (a reload can overlap with a page). */
export function appendPage(shown: HistoryEntry[], page: HistoryEntry[]): HistoryEntry[] {
  const known = new Set(shown.map((entry) => entry.id));
  return [...shown, ...page.filter((entry) => !known.has(entry.id))];
}
