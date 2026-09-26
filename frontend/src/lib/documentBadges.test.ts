import { describe, expect, it } from "vitest";
import type { DocumentDto } from "../api/types";
import { documentBadges } from "./documentBadges";

interface Shape {
  processing?: string;
  analysis?: string;
  text?: string;
  placementSuggestion?: boolean;
}

const document = ({ processing = "ARCHIVED", analysis = "CLASSIFIED", text = "RECOGNIZED", placementSuggestion }: Shape) =>
  ({
    processing: { state: processing },
    analysis: { state: analysis },
    textRecognition: { state: text },
    classification: { placementSuggestion: placementSuggestion ? { path: [] } : null },
  }) as unknown as DocumentDto;

const labels = (shape: Shape) => documentBadges(document(shape)).map((badge) => badge.labelKey);

describe("documentBadges", () => {
  it("shows nothing for an archived, readable and settled document", () => {
    expect(labels({})).toEqual([]);
    expect(labels({ analysis: "NOT_REQUESTED" })).toEqual([]);
    expect(labels({ text: "PENDING" })).toEqual([]);
    expect(labels({ text: "UNAVAILABLE" })).toEqual([]);
  });

  it("names a document that is still processed or whose archival failed", () => {
    expect(labels({ processing: "RECEIVED", analysis: "WAITING_FOR_ARCHIVE", text: "PENDING" })).toEqual([
      "documents.stateShort.RECEIVED",
    ]);
    expect(labels({ processing: "SUBMITTED", analysis: "WAITING_FOR_ARCHIVE" })).toEqual([
      "documents.stateShort.SUBMITTED",
    ]);
    expect(labels({ processing: "FAILED", analysis: "WAITING_FOR_ARCHIVE" })).toEqual(["documents.stateShort.FAILED"]);
  });

  it("names a running, failed or unavailable analysis and suggestions to review", () => {
    expect(labels({ analysis: "PENDING" })).toEqual(["analysis.stateShort.PENDING"]);
    expect(labels({ analysis: "NEEDS_REVIEW" })).toEqual(["analysis.stateShort.NEEDS_REVIEW"]);
    expect(labels({ analysis: "FAILED" })).toEqual(["analysis.stateShort.FAILED"]);
    expect(labels({ analysis: "UNAVAILABLE" })).toEqual(["analysis.stateShort.UNAVAILABLE"]);
    expect(labels({ placementSuggestion: true })).toEqual(["placement.badge"]);
  });

  it("names a document without recognized text with the text state as badge style", () => {
    expect(documentBadges(document({ text: "NONE" }))).toEqual([
      { state: "TEXT_NONE", labelKey: "documents.text.badge.NONE" },
    ]);
    expect(labels({ text: "LITTLE" })).toEqual(["documents.text.badge.LITTLE"]);
  });

  it("keeps several deviations in a stable order", () => {
    expect(labels({ analysis: "NEEDS_REVIEW", text: "NONE", placementSuggestion: true })).toEqual([
      "analysis.stateShort.NEEDS_REVIEW",
      "documents.text.badge.NONE",
      "placement.badge",
    ]);
  });
});
