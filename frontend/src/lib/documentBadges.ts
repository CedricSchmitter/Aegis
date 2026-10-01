import type { DocumentDto } from "../api/types";
import { lacksText } from "./textRecognition";

/** A state badge on a document card: the style state and the catalog key of its short label. */
export interface DocumentBadge {
  state: string;
  labelKey: string;
}

/**
 * Analysis states worth a badge: running, failed or waiting for a member, including an upload that waits for a member
 * with AI access, or waiting for the next day's AI budget. A finished, never-requested or not-yet-startable analysis stays quiet; waiting for the archive is
 * already told by the processing badge.
 */
const VISIBLE_ANALYSIS = new Set(["PENDING", "NEEDS_REVIEW", "FAILED", "UNAVAILABLE", "NOT_CLASSIFIED", "WAITING_FOR_BUDGET"]);

/**
 * The badges of a document card. Only what deviates from an archived, readable, settled document gets one, so the
 * usual "archived" state is not repeated on every card (issue #83, F10).
 */
export function documentBadges(document: DocumentDto): DocumentBadge[] {
  const badges: DocumentBadge[] = [];
  const processing = document.processing.state;
  if (processing !== "ARCHIVED") {
    badges.push({ state: processing, labelKey: `documents.stateShort.${processing}` });
  }
  const analysis = document.analysis.state;
  if (VISIBLE_ANALYSIS.has(analysis)) {
    badges.push({ state: analysis, labelKey: `analysis.stateShort.${analysis}` });
  }
  if (lacksText(document.textRecognition)) {
    const text = document.textRecognition.state;
    badges.push({ state: `TEXT_${text}`, labelKey: `documents.text.badge.${text}` });
  }
  if (document.classification.placementSuggestion) {
    badges.push({ state: "NEEDS_REVIEW", labelKey: "placement.badge" });
  }
  return badges;
}
