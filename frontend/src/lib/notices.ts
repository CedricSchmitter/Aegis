import type { TFunction } from "i18next";
import type { NoticeSummary } from "../api/types";
import { failureReasonText } from "./failureReasons";
import { formatDate } from "./format";

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

/** The localized text of a notice from its stable kind and parameters. */
export function noticeText(t: TFunction, notice: NoticeSummary, language: string): string {
  const params = notice.params ?? {};
  if (notice.kind === "REVIEW_ITEM" && describesReviewCard(params)) {
    return reviewTitle(t, params.titleKey, params);
  }
  const values: Record<string, string> = { ...params };
  if (notice.kind === "CONTRACT_CANCELLATION_REMINDER") {
    values.cancellationDeadline = params.cancellationDeadline ? formatDate(params.cancellationDeadline, language) : "";
    values.contractEnd = params.contractEnd ? formatDate(params.contractEnd, language) : "";
  }
  if (notice.kind === "DOCUMENT_ARCHIVAL_FAILED") values.reason = failureReasonText(t, params.code);
  return String(t(`notices.kind.${notice.kind}`, { ...values, defaultValue: notice.kind }));
}
