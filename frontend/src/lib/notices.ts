import type { TFunction } from "i18next";
import type { NoticeSummary } from "../api/types";
import { formatDate } from "./format";

/**
 * Review card title keys with the parameters their text needs. A REVIEW_ITEM notice carries the card's key and
 * parameters (issue #91); notices published before that carry the key only and keep the generic text.
 */
export const REVIEW_TITLE_PARAMS: Record<string, readonly string[]> = {
  "review.archiveFailed": ["title", "errorCode"],
  "review.analysisFailed": ["title", "errorCode"],
  "review.classification": ["title"],
  "review.contractFact": ["contract", "type", "value"],
  "review.contractConflict": ["contract", "type"],
  "review.noText": ["title"],
};

/** Presentation of a review card title: contract fact types are shown by their localized name. */
export function reviewTitle(t: TFunction, titleKey: string, params: Record<string, string>): string {
  const type = params.type ? String(t(`contracts.factType.${params.type}`, { defaultValue: params.type })) : undefined;
  return String(t(titleKey, type ? { ...params, type } : params));
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
  const values = notice.kind === "CONTRACT_CANCELLATION_REMINDER"
    ? {
        ...params,
        cancellationDeadline: params.cancellationDeadline ? formatDate(params.cancellationDeadline, language) : "",
        contractEnd: params.contractEnd ? formatDate(params.contractEnd, language) : "",
      }
    : params;
  return String(t(`notices.kind.${notice.kind}`, { ...values, defaultValue: notice.kind }));
}
