import type { NoticeSummary } from "../api/types";

/** Dispatched after something changed the inbox badge: a card was decided or notices were read. */
export const INBOX_CHANGED = "aegis:inbox-changed";

/** Anchor of the messages section, used by old `/notices` links. */
export const MESSAGES_ANCHOR = "messages";

/**
 * Where an old inbox link leads since "Prüfen" and "Hinweise" became one inbox (issue #83). `/review?item=<id>` keeps
 * its card focus, `/notices` opens the messages section; links from notices, bookmarks and the share flow keep working.
 */
export function legacyInboxTarget(pathname: string, search: string): string {
  if (pathname === "/notices") return `/inbox${search}#${MESSAGES_ANCHOR}`;
  return `/inbox${search}`;
}

/** The page a message opens, or null when it has no target. */
export function noticeTarget(notice: NoticeSummary): string | null {
  if (notice.linkType === "TASK" && notice.linkId) return `/tasks/${notice.linkId}`;
  if (notice.linkType === "DOCUMENT" && notice.linkId) return `/documents/${notice.linkId}`;
  if (notice.linkType === "CONTRACT" && notice.linkId) return `/contracts/${notice.linkId}`;
  if (notice.linkType === "REVIEW" && notice.linkId) return `/inbox?item=${encodeURIComponent(notice.linkId)}`;
  if (notice.linkType === "STATUS") return "/status";
  return null;
}

/** Card frame and signal colour (ADR-0019): red for anything broken, the data colour for what is new. */
export type InboxTone = "danger" | "data" | null;

/** Review reasons that report a failed step; they are broken, so their card is red. */
const FAILED_REASONS = new Set(["ARCHIVAL_FAILED", "ANALYSIS_FAILED"]);

/** A failed archival or analysis is broken; everything else on a review card only asks for a decision. */
export function reviewTone(reason: string): InboxTone {
  return FAILED_REASONS.has(reason) ? "danger" : null;
}

/** Operational messages that report something broken: a failed backup, low disk space or a job that stopped. */
const BROKEN_KINDS = new Set(["BACKUP_FAILED", "DISK_SPACE_LOW", "JOB_STALE"]);

/** A message about something broken stays red while unread and after; any other unread message is new data. */
export function noticeTone(notice: Pick<NoticeSummary, "kind" | "readAt">): InboxTone {
  if (BROKEN_KINDS.has(notice.kind)) return "danger";
  return notice.readAt ? null : "data";
}

/** The page a review card opens and the words of its link, or null when the card has no target. */
export function reviewTarget(linkType: string, linkId: string): { to: string; labelKey: string } | null {
  if (linkType === "DOCUMENT") return { to: `/documents/${linkId}`, labelKey: "review.openDocument" };
  if (linkType === "CONTRACT") return { to: `/contracts/${linkId}`, labelKey: "review.openContract" };
  return null;
}
