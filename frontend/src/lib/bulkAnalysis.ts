import type { TFunction } from "i18next";
import type { BulkAnalysisDto, BulkScopeDto } from "../api/types";
import { failureReasonText } from "./failureReasons";

/** How often the page checks back while a bulk classification runs. */
export const RUN_POLL_MS = 4000;

/** Picker values that are not a folder id. */
export const SCOPE_ALL = "ALL";
export const SCOPE_UNSORTED = "UNSORTED";

/** The scope a picker value stands for: the whole collection, Unsorted, or one folder (issue #115). */
export function scopeOf(value: string, includeSubfolders: boolean): BulkScopeDto {
  if (value === SCOPE_ALL || value === SCOPE_UNSORTED) return { kind: value, includeSubfolders: false };
  return { kind: "FOLDER", folderId: value, includeSubfolders };
}

/** How a run's scope reads: its kind, or the folder path as it is now. */
export function scopeName(t: TFunction, scope: BulkScopeDto): string {
  if (scope.kind !== "FOLDER") return String(t(`bulkAnalysis.scopeName.${scope.kind === "UNSORTED" ? "UNSORTED" : "ALL"}`));
  const path = scope.folderPath ?? [];
  if (path.length === 0) return String(t("bulkAnalysis.scopeName.deletedFolder"));
  return String(t("bulkAnalysis.scopeName.FOLDER", { path: path.join(" / ") }));
}

/** Documents of a run that need nothing more from it: finished, skipped or cancelled. */
export function doneCount(run: Pick<BulkAnalysisDto, "total" | "pending" | "inFlight">): number {
  return Math.max(0, run.total - run.pending - run.inFlight);
}

export function progressPercent(run: Pick<BulkAnalysisDto, "total" | "pending" | "inFlight">): number {
  return run.total === 0 ? 100 : Math.round((doneCount(run) / run.total) * 100);
}

export const SKIP_REASONS = ["trashed", "notArchived", "classified", "pending", "notFound", "other"] as const;
export type SkipReason = (typeof SKIP_REASONS)[number];

const SKIPS: Record<string, SkipReason> = {
  "document.trashed": "trashed",
  "document.notArchived": "notArchived",
  "analysis.classified": "classified",
  "analysis.pending": "pending",
  "document.notFound": "notFound",
};

/** Why a document was skipped, from the stable code the server stores; unknown codes get a generic reason. */
export function skipReason(code: string): SkipReason {
  return SKIPS[code] ?? "other";
}

/**
 * Why a run stopped: the AI boundary's refusal (not configured, member not allowed, ...) as the error text the rest of
 * the app shows for it, otherwise a short failure reason.
 */
export function stopReasonText(t: TFunction, code: string | null | undefined): string {
  if (!code) return failureReasonText(t, code);
  return String(t(`errors.${code}`, { defaultValue: failureReasonText(t, code) }));
}

/** The run a page shows first: the one asked for, else a running one, else the newest. */
export function shownRun(runs: BulkAnalysisDto[], requested: string | null): BulkAnalysisDto | null {
  return runs.find((run) => run.id === requested) ?? runs.find((run) => run.state === "RUNNING") ?? runs[0] ?? null;
}
