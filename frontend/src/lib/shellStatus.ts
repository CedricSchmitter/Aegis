import type { TFunction } from "i18next";
import type { HealthCheck } from "../api/types";
import { formatDateTime } from "./format";
import { areaLabel, problemCount } from "./status";

/**
 * What the shell says about the status overview (ADR-0018) in the header, the status bar, the avatar and the status
 * line on Today (issue #27): red only when something is broken, the data colour when everything works, grey while
 * the overview is not known.
 */
export type StatusTone = "ok" | "problem" | "unknown";

export interface StatusSummary {
  tone: StatusTone;
  /** Areas with a problem, in the order of the overview. */
  problemAreas: string[];
  /** When the external backup last succeeded, if known. */
  lastBackupAt: string | null;
}

export function summarizeStatus(checks: HealthCheck[] | null | undefined): StatusSummary {
  if (!checks) return { tone: "unknown", problemAreas: [], lastBackupAt: null };
  const problems = checks.filter((check) => check.state === "PROBLEM");
  return {
    tone: problemCount(checks) > 0 ? "problem" : "ok",
    problemAreas: problems.map((check) => check.area),
    lastBackupAt: checks.find((check) => check.area === "backup")?.lastSuccessAt ?? null,
  };
}

/** "Alles in Ordnung", "Problem: Backup" or "2 Bereiche brauchen Aufmerksamkeit". */
export function statusHeadline(t: TFunction, summary: StatusSummary): string {
  if (summary.tone === "unknown") return String(t("shell.status.unknown"));
  if (summary.tone === "ok") return String(t("shell.status.ok"));
  if (summary.problemAreas.length === 1) {
    return String(t("shell.status.oneProblem", { area: areaLabel(t, summary.problemAreas[0]) }));
  }
  return String(t("shell.status.problems", { count: summary.problemAreas.length }));
}

/**
 * The quiet detail next to the headline: the affected areas when the headline only counts them, or when the backup
 * last ran. A single problem names its area in the headline already.
 */
export function statusDetail(t: TFunction, summary: StatusSummary, language: string): string | null {
  if (summary.tone === "problem") {
    if (summary.problemAreas.length < 2) return null;
    const areas = summary.problemAreas.map((area) => areaLabel(t, area));
    return new Intl.ListFormat(language, { type: "conjunction" }).format(areas);
  }
  if (summary.tone === "ok" && summary.lastBackupAt) {
    return String(t("shell.status.lastBackup", { date: formatDateTime(summary.lastBackupAt, language) }));
  }
  return null;
}
