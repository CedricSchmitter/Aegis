import type { TFunction } from "i18next";
import type { HealthCheck } from "../api/types";
import { formatDateTime } from "./format";
import { backupHintText, backupReasonText, diskName } from "./storage";

/**
 * The status overview that answers "is everything OK?" (issue #83, F4). The API sends stable area and reason codes
 * with stable parameters; this module turns them into words and never shows a code or parameter as it is.
 */
export const CHECK_AREAS = ["database", "archive", "backup", "storage", "ai", "jobs"] as const;

export const CHECK_REASONS = [
  "database.connected",
  "database.unavailable",
  "archive.reachable",
  "archive.unavailable",
  "backup.ok",
  "backup.running",
  "backup.failed",
  "backup.stale",
  "backup.interrupted",
  "backup.notConfigured",
  "backup.unknown",
  "storage.ok",
  "storage.low",
  "storage.unmeasured",
  "ai.connected",
  "ai.unchecked",
  "ai.credentialsMissing",
  "ai.providerFailure",
  "jobs.notRunning",
  "probe.failed",
] as const;

export const CHECK_STATES = ["OK", "UNCHECKED", "PROBLEM"] as const;

/** Jobs, providers and failure categories with their own name in the catalogs; anything else gets a generic word. */
export const KNOWN_JOBS = ["reconcile-review-inbox", "check-archive-text", "reconcile-documents", "watch-job-health"];
const KNOWN_PROVIDERS = ["anthropic", "claude-code"];
const KNOWN_AI_FAILURES = ["NOT_CONFIGURED", "AUTHENTICATION", "MODEL_UNAVAILABLE", "INVALID_REQUEST", "RATE_LIMITED",
  "PROVIDER_UNAVAILABLE", "TIMEOUT"];

const has = (list: readonly string[], value: string | undefined): value is string => value !== undefined && list.includes(value);

export function areaLabel(t: TFunction, area: string): string {
  return has(CHECK_AREAS, area) ? String(t(`status.area.${area}`)) : String(t("status.area.other"));
}

export function stateLabel(t: TFunction, state: HealthCheck["state"]): string {
  return String(t(`status.checkState.${state}`));
}

/** Only a problem calls for attention; an unchecked AI connection is neutral. */
export function problemCount(checks: HealthCheck[]): number {
  return checks.filter((check) => check.state === "PROBLEM").length;
}

function list(items: string[], language: string): string {
  return new Intl.ListFormat(language, { type: "conjunction" }).format(items);
}

function names(value: string | undefined): string[] {
  return (value ?? "").split(",").filter((name) => name.length > 0);
}

export function jobName(t: TFunction, job: string): string {
  return has(KNOWN_JOBS, job) ? String(t(`status.jobs.name.${job}`)) : String(t("status.jobs.unnamed"));
}

/** The sentence of one overview line in the viewer's language. */
export function checkText(t: TFunction, check: HealthCheck, language: string): string {
  if (!has(CHECK_REASONS, check.reason)) return String(t(`status.check.fallback.${check.state}`));
  const params = check.params ?? {};
  const values: Record<string, string> = {
    date: check.lastSuccessAt ? formatDateTime(check.lastSuccessAt, language) : String(t("status.never")),
  };
  switch (check.reason) {
    case "backup.failed":
    case "backup.unknown":
      values.reason = backupReasonText(t, params.failureCode);
      break;
    case "storage.low":
    case "storage.unmeasured":
      values.disks = list(names(params.disks).map((disk) => diskName(t, disk)), language);
      break;
    case "jobs.notRunning":
      values.jobs = list(names(params.jobs).map((job) => jobName(t, job)), language);
      break;
    case "ai.credentialsMissing":
      values.provider = has(KNOWN_PROVIDERS, params.provider)
        ? String(t(`ai.provider.${params.provider}`))
        : String(t("status.check.unknownProvider"));
      break;
    case "ai.providerFailure":
      values.failure = has(KNOWN_AI_FAILURES, params.failure)
        ? String(t(`ai.failure.${params.failure}`))
        : String(t("status.check.unknownFailure"));
      break;
  }
  return String(t(`status.check.${check.reason}`, values));
}

/** What to check or do for a backup problem; other areas explain themselves in their sentence. */
export function checkHint(t: TFunction, check: HealthCheck): string | null {
  switch (check.reason) {
    case "backup.failed":
    case "backup.unknown":
      return backupHintText(t, check.params?.failureCode);
    case "backup.stale":
      return String(t("status.backup.stateHint.STALE"));
    case "backup.interrupted":
      return String(t("status.backup.stateHint.INTERRUPTED"));
    default:
      return null;
  }
}

/** "Zuletzt in Ordnung: …" under a problem, when the area worked before; the backup line names its date itself. */
export function lastOkText(t: TFunction, check: HealthCheck, language: string): string | null {
  if (check.state !== "PROBLEM" || !check.lastSuccessAt || check.area === "backup") return null;
  return String(t("status.check.lastOk", { date: formatDateTime(check.lastSuccessAt, language) }));
}

/** Problem reasons without a backup hint that have their own "what now?" text; any other gets the generic one. */
export const WHAT_NOW_REASONS = [
  "database.unavailable",
  "archive.unavailable",
  "backup.notConfigured",
  "storage.low",
  "storage.unmeasured",
  "ai.credentialsMissing",
  "ai.providerFailure",
  "jobs.notRunning",
  "probe.failed",
] as const;

/**
 * "Was jetzt?" under a broken area (issue #27): what to check or do. A backup problem names its own hint and adds that
 * the documents in Aegis are not affected.
 */
export function whatNowText(t: TFunction, check: HealthCheck): string {
  const backupHint = check.area === "backup" ? checkHint(t, check) : null;
  if (backupHint) return `${backupHint} ${String(t("status.whatNow.documentsSafe"))}`;
  if (has(WHAT_NOW_REASONS, check.reason)) return String(t(`status.whatNow.${check.reason}`));
  return String(t("status.whatNow.fallback"));
}
