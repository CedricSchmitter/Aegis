import type { TFunction } from "i18next";

/**
 * Short, localized reasons for failed archival and analysis (issue #96). Review cards and notices keep the stable
 * failure code in their stored parameters; only the presentation turns it into words. The full code stays visible
 * on the document detail page.
 */
export const FAILURE_REASONS = [
  "archiveUnreachable",
  "archiveRejected",
  "archiveUnexpected",
  "originalMissing",
  "aiNotConfigured",
  "aiMemberNotAllowed",
  "aiUnavailable",
  "aiAccessRefused",
  "aiRequestRejected",
  "aiLimitReached",
  "aiOutputUnusable",
  "sourceChanged",
  "interrupted",
  "unknown",
] as const;

export type FailureReason = (typeof FAILURE_REASONS)[number];

const EXACT: Record<string, FailureReason> = {
  // Archival: ArchiveDocumentTask and the Paperless adapter.
  "paperless.unavailable": "archiveUnreachable",
  "paperless.timeout": "archiveUnreachable",
  "paperless.rejected": "archiveRejected",
  "paperless.consumptionFailed": "archiveRejected",
  "paperless.revoked": "archiveUnexpected",
  "paperless.noTaskId": "archiveUnexpected",
  "paperless.noDocumentId": "archiveUnexpected",
  "document.stagingMissing": "originalMissing",
  // Analysis that could not be queued (DocumentAnalysis.unavailableCode) or ended without a run.
  "ai.notConfigured": "aiNotConfigured",
  "ai.provider.notConfigured": "aiNotConfigured",
  "ai.price.unknown": "aiNotConfigured",
  "ai.provider.memberNotAllowed": "aiMemberNotAllowed",
  "auth.forbidden": "aiMemberNotAllowed",
  // Failed analysis runs (RunFailure.code in the assistant context).
  "provider.unavailable": "aiUnavailable",
  "provider.unreachable": "aiUnavailable",
  "provider.overloaded": "aiUnavailable",
  "provider.busy": "aiUnavailable",
  "provider.timeout": "aiUnavailable",
  "provider.rateLimited": "aiUnavailable",
  "provider.cliUnavailable": "aiUnavailable",
  "provider.error": "aiUnavailable",
  "model.timeout": "aiUnavailable",
  "provider.authentication": "aiAccessRefused",
  "provider.permission": "aiAccessRefused",
  "provider.billing": "aiAccessRefused",
  "provider.modelNotFound": "aiAccessRefused",
  "provider.invalidRequest": "aiRequestRejected",
  "provider.requestTooLarge": "aiRequestRejected",
  "provider.toolsUnsupported": "aiRequestRejected",
  "provider.conversationUnsupported": "aiRequestRejected",
  "tool.notGranted": "aiRequestRejected",
  "provider.invalidResponse": "aiOutputUnusable",
  "model.refused": "aiOutputUnusable",
  "run.cancelled": "interrupted",
  "attempt.interrupted": "interrupted",
};

/** Code families whose members share one reason; `ai.run.*` is built by AnalysisViews from the run state. */
const FAMILIES: readonly (readonly [string, FailureReason])[] = [
  ["ai.workload.", "aiNotConfigured"],
  ["budget.", "aiLimitReached"],
  ["output.", "aiOutputUnusable"],
  ["source.", "sourceChanged"],
  ["ai.run.", "interrupted"],
];

/** The reason for a stable failure code; unknown or missing codes get the generic reason, never the raw code. */
export function failureReason(code: string | null | undefined): FailureReason {
  if (!code) return "unknown";
  return EXACT[code] ?? FAMILIES.find(([prefix]) => code.startsWith(prefix))?.[1] ?? "unknown";
}

/** The localized short reason, written to continue a sentence such as "… failed: {{reason}}." */
export function failureReasonText(t: TFunction, code: string | null | undefined): string {
  return String(t(`review.failureReason.${failureReason(code)}`));
}
