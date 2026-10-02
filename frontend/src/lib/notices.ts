import type { TFunction } from "i18next";
import type { NoticeSummary } from "../api/types";
import { failureReasonText } from "./failureReasons";
import { formatBytes, formatDate, formatDateTime } from "./format";
import { backupReasonText, diskName } from "./storage";

/**
 * Review card title keys with the parameters their text needs. A failure code (`errorCode`) is stored as is and shown
 * as a short localized reason.
 */
export const REVIEW_TITLE_PARAMS: Record<string, readonly string[]> = {
  "review.archiveFailed": ["title", "errorCode"],
  "review.analysisFailed": ["title", "errorCode"],
  "review.classification": ["title"],
  "review.contractFact": ["contract", "type", "value"],
  "review.contractConflict": ["contract", "type"],
  "review.noText": ["title"],
  "review.bulkAnalysis": ["total", "classified", "needsReview", "failed"],
};

/**
 * Presentation of a review card title: contract fact types are shown by their localized name and failure codes by a
 * short localized reason.
 */
export function reviewTitle(t: TFunction, titleKey: string, params: Record<string, string>): string {
  const values: Record<string, string> = { ...params };
  if (params.type) values.type = String(t(`contracts.factType.${params.type}`, { defaultValue: params.type }));
  if (REVIEW_TITLE_PARAMS[titleKey]?.includes("errorCode")) values.reason = failureReasonText(t, params.errorCode);
  return String(t(titleKey, values));
}

/**
 * A JOB_STALE notice names the job by its localized status page name and its state at the start of the
 * outage: stale since the last success, stale without any success, or no longer scheduled.
 */
function jobStaleText(t: TFunction, params: Record<string, string>, language: string): string {
  const job = String(t(`status.jobs.name.${params.job}`, { defaultValue: params.job }));
  const variant = params.state === "STALE" && !params.lastSuccessAt ? "NEVER_SUCCEEDED" : params.state;
  return String(
    t(`notices.jobStale.${variant}`, {
      job,
      lastSuccess: formatDateTime(params.lastSuccessAt, language),
      defaultValue: t("notices.kind.JOB_STALE"),
    }),
  );
}

/**
 * A BACKUP_FAILED notice names the backup state at the start of the incident: a failed attempt with its
 * localized reason, no success for more than 26 hours, an interrupted run or an unreadable state.
 */
function backupFailedText(t: TFunction, params: Record<string, string>, language: string): string {
  const variant = params.state === "FAILED" && !params.lastSuccessAt ? "FAILED_NEVER" : params.state;
  return String(
    t(`notices.backupFailed.${variant}`, {
      reason: backupReasonText(t, params.reason),
      lastSuccess: formatDateTime(params.lastSuccessAt, language),
      defaultValue: t("notices.kind.BACKUP_FAILED"),
    }),
  );
}

/** A DISK_SPACE_LOW notice names the disk and its free space when the incident opened. */
function diskSpaceLowText(t: TFunction, params: Record<string, string>, language: string): string {
  const free = Number(params.freeBytes);
  const total = Number(params.totalBytes);
  if (!Number.isFinite(free) || !Number.isFinite(total)) return String(t("notices.kind.DISK_SPACE_LOW"));
  return String(
    t("notices.diskSpaceLow", {
      disk: diskName(t, params.disk),
      free: formatBytes(free, t, language),
      total: formatBytes(total, t, language),
    }),
  );
}

/**
 * The localized text of a message from its stable kind and parameters. Notices announcing a review card are not
 * listed; the inbox shows their card with {@link reviewTitle}.
 */
export function noticeText(t: TFunction, notice: NoticeSummary, language: string): string {
  const params = notice.params ?? {};
  if (notice.kind === "JOB_STALE" && params.job && params.state) return jobStaleText(t, params, language);
  if (notice.kind === "BACKUP_FAILED" && params.state) return backupFailedText(t, params, language);
  if (notice.kind === "DISK_SPACE_LOW" && params.disk) return diskSpaceLowText(t, params, language);
  const values: Record<string, string> = { ...params };
  if (notice.kind === "CONTRACT_CANCELLATION_REMINDER") {
    values.cancellationDeadline = params.cancellationDeadline ? formatDate(params.cancellationDeadline, language) : "";
    values.contractEnd = params.contractEnd ? formatDate(params.contractEnd, language) : "";
  }
  return String(t(`notices.kind.${notice.kind}`, { ...values, defaultValue: notice.kind }));
}
