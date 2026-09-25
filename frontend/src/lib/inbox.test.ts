import { describe, expect, it } from "vitest";
import type { NoticeSummary } from "../api/types";
import { legacyInboxTarget, noticeTarget } from "./inbox";

function notice(linkType: string | null, linkId: string | null): NoticeSummary {
  return { id: "n1", kind: "TASK_ASSIGNED", params: {}, linkType, linkId, createdAt: "2026-09-25T10:00:00Z", readAt: null };
}

describe("inbox links", () => {
  it("send old review links to the inbox and keep the focused card", () => {
    expect(legacyInboxTarget("/review", "")).toBe("/inbox");
    expect(legacyInboxTarget("/review", "?item=5f0c")).toBe("/inbox?item=5f0c");
  });

  it("send old notice links to the messages section", () => {
    expect(legacyInboxTarget("/notices", "")).toBe("/inbox#messages");
  });

  it("open a message's target, and a review card inside the inbox", () => {
    expect(noticeTarget(notice("TASK", "t1"))).toBe("/tasks/t1");
    expect(noticeTarget(notice("STATUS", "watch-job-health"))).toBe("/status");
    expect(noticeTarget(notice("REVIEW", "a b"))).toBe("/inbox?item=a%20b");
    expect(noticeTarget(notice(null, null))).toBeNull();
  });
});
