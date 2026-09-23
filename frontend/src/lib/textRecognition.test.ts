import { describe, expect, it } from "vitest";
import type { DocumentDto } from "../api/types";
import { awaitingTextCheck, lacksText, TEXT_CHECK_WAIT_MS } from "./textRecognition";

type State = DocumentDto["textRecognition"]["state"];

const recognition = (state: State) => ({ state, recognizedCharacters: null, checkedAt: null, nextAttemptAt: null });

const document = (processing: string, state: State, archivedAt: string | null) =>
  ({ processing: { state: processing, archivedAt }, textRecognition: recognition(state) }) as unknown as DocumentDto;

describe("lacksText", () => {
  it("flags no or almost no recognized text and nothing else", () => {
    expect(lacksText(recognition("NONE"))).toBe(true);
    expect(lacksText(recognition("LITTLE"))).toBe(true);
    for (const state of ["NOT_ARCHIVED", "PENDING", "UNAVAILABLE", "RECOGNIZED"] as const) {
      expect(lacksText(recognition(state)), state).toBe(false);
    }
  });
});

describe("awaitingTextCheck", () => {
  const archivedAt = "2026-09-23T10:00:00Z";
  const at = Date.parse(archivedAt);

  it("keeps refreshing a freshly archived document until its text check arrived", () => {
    expect(awaitingTextCheck(document("ARCHIVED", "PENDING", archivedAt), at + 5_000)).toBe(true);
    expect(awaitingTextCheck(document("ARCHIVED", "NONE", archivedAt), at + 5_000)).toBe(false);
    expect(awaitingTextCheck(document("ARCHIVED", "UNAVAILABLE", archivedAt), at + 5_000)).toBe(false);
  });

  it("stops after a bounded time and ignores documents that are not archived", () => {
    expect(awaitingTextCheck(document("ARCHIVED", "PENDING", archivedAt), at + TEXT_CHECK_WAIT_MS)).toBe(false);
    // Documents archived before the check existed wait for the sweep without polling.
    expect(awaitingTextCheck(document("ARCHIVED", "PENDING", "2026-09-01T10:00:00Z"), at)).toBe(false);
    expect(awaitingTextCheck(document("SUBMITTED", "NOT_ARCHIVED", null), at)).toBe(false);
    expect(awaitingTextCheck(document("ARCHIVED", "PENDING", null), at)).toBe(false);
  });
});
