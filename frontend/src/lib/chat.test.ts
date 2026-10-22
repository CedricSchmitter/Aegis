import { describe, expect, it } from "vitest";
import type { ChatCitationDto } from "../api/types";
import { answerSegments, citationTarget, progressSteps, turnOpen, turnRetryable, waitingMessage } from "./chat";

const citation = (number: number, kind: ChatCitationDto["kind"] = "DOCUMENT"): ChatCitationDto => ({
  number,
  kind,
  sourceId: `id-${number}`,
  title: null,
  quote: null,
  page: null,
  check: "VERIFIED",
});

describe("answer segments", () => {
  it("turns markers of known citations into links and keeps the text around them", () => {
    expect(answerSegments("Bis 31.10. [1], berechnet [1, 2].", [citation(1), citation(2)])).toEqual([
      { kind: "text", text: "Bis 31.10. " },
      { kind: "citation", number: 1 },
      { kind: "text", text: ", berechnet " },
      { kind: "citation", number: 1 },
      { kind: "citation", number: 2 },
      { kind: "text", text: "." },
    ]);
  });

  it("drops bold markers, because answers are shown as plain text", () => {
    expect(answerSegments("**Telekom** zuerst [1]", [citation(1)])).toEqual([
      { kind: "text", text: "Telekom zuerst " },
      { kind: "citation", number: 1 },
    ]);
  });

  it("leaves bracketed numbers that name no citation as text", () => {
    expect(answerSegments("Vertrag [4711] gilt [3].", [citation(1)])).toEqual([{ kind: "text", text: "Vertrag [4711] gilt [3]." }]);
  });
});

describe("turn state", () => {
  it("keeps checking waiting and working questions only", () => {
    expect(turnOpen({ state: "WAITING" })).toBe(true);
    expect(turnOpen({ state: "WORKING" })).toBe(true);
    expect(turnOpen({ state: "ANSWERED" })).toBe(false);
    expect(turnOpen(undefined)).toBe(false);
  });

  it("offers a retry after failure or cancellation without an answer", () => {
    expect(turnRetryable({ state: "FAILED", answer: undefined })).toBe(true);
    expect(turnRetryable({ state: "CANCELLED", answer: undefined })).toBe(true);
    expect(turnRetryable({ state: "WORKING", answer: undefined })).toBe(false);
  });

  it("names a resume time only for the exact renewal of the daily budget", () => {
    const at = "2026-10-02T22:00:00Z";
    expect(waitingMessage({ failureCode: "budget.daily", nextAttemptAt: at })).toEqual({ key: "chat.waitingUntil", at });
    expect(waitingMessage({ failureCode: "provider.rateLimited", nextAttemptAt: at })).toEqual({ key: "chat.waitingRateLimited" });
    expect(waitingMessage({ failureCode: "provider.unavailable", nextAttemptAt: at })).toEqual({ key: "chat.waitingRetry" });
    expect(waitingMessage({ failureCode: null, nextAttemptAt: null })).toEqual({ key: "chat.waiting" });
  });
});

describe("progress steps", () => {
  it("names tool steps, counts repeats in a row and leaves model calls out", () => {
    expect(
      progressSteps([
        { kind: "ATTEMPT", tool: null, at: "t" },
        { kind: "MODEL", tool: null, at: "t" },
        { kind: "TOOL", tool: "search_documents", at: "t" },
        { kind: "TOOL", tool: "search_document_text", at: "t" },
        { kind: "MODEL", tool: null, at: "t" },
        { kind: "TOOL", tool: "find_in_document", at: "t" },
        { kind: "TOOL", tool: "read_document", at: "t" },
        { kind: "TOOL", tool: "something_new", at: "t" },
      ]),
    ).toEqual([
      { key: "chat.progress.started", count: 1 },
      { key: "chat.progress.searchDocuments", count: 2 },
      { key: "chat.progress.findInDocument", count: 1 },
      { key: "chat.progress.readDocument", count: 1 },
      { key: "chat.progress.otherTool", count: 1 },
    ]);
  });
});

describe("citation targets", () => {
  it("open the page of the cited record", () => {
    expect(citationTarget(citation(1))).toBe("/documents/id-1");
    expect(citationTarget(citation(2, "CONTRACT"))).toBe("/contracts/id-2");
    expect(citationTarget(citation(3, "TASK"))).toBe("/tasks/id-3");
  });
});
