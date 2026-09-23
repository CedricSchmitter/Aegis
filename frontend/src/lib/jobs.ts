import type { TFunction } from "i18next";
import type { SystemStatus } from "../api/types";

export type JobHealth = SystemStatus["jobs"][number];

/** States in which a recurring job does not run as planned (ADR-0013); a job waiting for its first run is fine. */
const ATTENTION = new Set<JobHealth["state"]>(["FAILING", "STALE", "NOT_SCHEDULED", "UNKNOWN"]);

export function needsAttention(job: JobHealth): boolean {
  return ATTENTION.has(job.state);
}

const DAY = 86_400;
const HOUR = 3_600;

/** How often a job runs, in the largest whole unit: "täglich", "alle 5 Minuten". */
export function jobInterval(t: TFunction, seconds: number): string {
  if (seconds >= DAY && seconds % DAY === 0) return String(t("status.jobs.every.days", { count: seconds / DAY }));
  if (seconds >= HOUR && seconds % HOUR === 0) return String(t("status.jobs.every.hours", { count: seconds / HOUR }));
  return String(t("status.jobs.every.minutes", { count: Math.max(1, Math.round(seconds / 60)) }));
}
