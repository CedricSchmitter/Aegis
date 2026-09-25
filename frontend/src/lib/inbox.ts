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
