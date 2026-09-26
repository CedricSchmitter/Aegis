import { describe, expect, it } from "vitest";
import type { NoticeSummary } from "../api/types";
import { legacyInboxTarget, noticeTarget, noticeTone, reviewTarget, reviewTone } from "./inbox";

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

describe("inbox tones", () => {
  it("colour failed processing red and leave other review cards neutral", () => {
    expect(reviewTone("ARCHIVAL_FAILED")).toBe("danger");
    expect(reviewTone("ANALYSIS_FAILED")).toBe("danger");
    expect(reviewTone("UNCERTAIN_CLASSIFICATION")).toBeNull();
    expect(reviewTone("CONFLICTING_FACTS")).toBeNull();
  });

  it("keep a broken backup red after it was read and mark other unread messages as new", () => {
    expect(noticeTone({ kind: "BACKUP_FAILED", readAt: null })).toBe("danger");
    expect(noticeTone({ kind: "BACKUP_FAILED", readAt: "2026-09-27T08:00:00Z" })).toBe("danger");
    expect(noticeTone({ kind: "DISK_SPACE_LOW", readAt: null })).toBe("danger");
    expect(noticeTone({ kind: "JOB_STALE", readAt: null })).toBe("danger");
    expect(noticeTone({ kind: "TASK_ASSIGNED", readAt: null })).toBe("data");
    expect(noticeTone({ kind: "TASK_ASSIGNED", readAt: "2026-09-27T08:00:00Z" })).toBeNull();
  });

  it("open a review card's document or contract", () => {
    expect(reviewTarget("DOCUMENT", "d1")).toEqual({ to: "/documents/d1", labelKey: "review.openDocument" });
    expect(reviewTarget("CONTRACT", "c1")).toEqual({ to: "/contracts/c1", labelKey: "review.openContract" });
    expect(reviewTarget("OTHER", "x")).toBeNull();
  });
});
