import type { TFunction } from "i18next";
import type { NoticeSummary } from "../api/types";
import { failureReasonText } from "./failureReasons";
import { formatDate, formatDateTime } from "./format";

/**
 * Review card title keys with the parameters their text needs. A REVIEW_ITEM notice carries the card's key and
 * parameters (issue #91); notices published before that carry the key only and keep the generic text. A failure
 * code (`errorCode`) is stored as is and shown as a short localized reason (issue #96).
 */
export const REVIEW_TITLE_PARAMS: Record<string, readonly string[]> = {
  "review.archiveFailed": ["title", "errorCode"],
  "review.analysisFailed": ["title", "errorCode"],
  "review.classification": ["title"],
  "review.contractFact": ["contract", "type", "value"],
  "review.contractConflict": ["contract", "type"],
  "review.noText": ["title"],
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

function describesReviewCard(params: Record<string, string>): boolean {
  const required = REVIEW_TITLE_PARAMS[params.titleKey ?? ""];
  return required !== undefined && required.every((name) => params[name] !== undefined);
}

/**
 * A JOB_STALE notice (issue #94) names the job by its localized status page name and its state at the start of the
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

/** The localized text of a notice from its stable kind and parameters. */
export function noticeText(t: TFunction, notice: NoticeSummary, language: string): string {
  const params = notice.params ?? {};
  if (notice.kind === "REVIEW_ITEM" && describesReviewCard(params)) {
    return reviewTitle(t, params.titleKey, params);
  }
  if (notice.kind === "JOB_STALE" && params.job && params.state) return jobStaleText(t, params, language);
  const values: Record<string, string> = { ...params };
  if (notice.kind === "CONTRACT_CANCELLATION_REMINDER") {
    values.cancellationDeadline = params.cancellationDeadline ? formatDate(params.cancellationDeadline, language) : "";
    values.contractEnd = params.contractEnd ? formatDate(params.contractEnd, language) : "";
  }
  if (notice.kind === "DOCUMENT_ARCHIVAL_FAILED") values.reason = failureReasonText(t, params.code);
  return String(t(`notices.kind.${notice.kind}`, { ...values, defaultValue: notice.kind }));
}
