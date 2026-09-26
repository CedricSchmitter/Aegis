import { describe, expect, it } from "vitest";
import type { DocumentDto } from "../api/types";
import { documentProgress, fileKind, thumbnailUrl, viewerSource } from "./documentView";

interface Shape {
  contentType?: string;
  processing?: string;
  text?: string;
  analysis?: string;
  categoryId?: string | null;
  archivePdf?: boolean;
  original?: boolean;
}

const document = ({
  contentType = "application/pdf",
  processing = "ARCHIVED",
  text = "RECOGNIZED",
  analysis = "NOT_REQUESTED",
  categoryId = null,
  archivePdf = false,
  original = true,
}: Shape) =>
  ({
    id: "d1",
    contentType,
    processing: { state: processing },
    textRecognition: { state: text },
    analysis: { state: analysis },
    classification: { categoryId },
    archivePdfAvailable: archivePdf,
    originalAvailable: original,
  }) as unknown as DocumentDto;

describe("fileKind", () => {
  it("tells photos, scanned PDFs and ordinary PDFs apart", () => {
    expect(fileKind(document({ contentType: "image/jpeg" }))).toBe("photo");
    expect(fileKind(document({ contentType: "image/heic", archivePdf: true }))).toBe("photo");
    expect(fileKind(document({ archivePdf: true }))).toBe("scan");
    expect(fileKind(document({}))).toBe("pdf");
  });
});

describe("documentProgress", () => {
  it("runs the archival after saving and marks a failed one", () => {
    expect(documentProgress(document({ processing: "RECEIVED", text: "PENDING" }))).toEqual({ completed: 1, current: "running" });
    expect(documentProgress(document({ processing: "SUBMITTED", text: "PENDING" }))).toEqual({ completed: 1, current: "running" });
    expect(documentProgress(document({ processing: "FAILED" }))).toEqual({ completed: 1, current: "failed" });
  });

  it("waits for text that was not found and runs while it is checked", () => {
    expect(documentProgress(document({ text: "PENDING" }))).toEqual({ completed: 2, current: "running" });
    expect(documentProgress(document({ text: "NONE" }))).toEqual({ completed: 2, current: "waiting" });
    expect(documentProgress(document({ text: "UNAVAILABLE" }))).toEqual({ completed: 2, current: "waiting" });
  });

  it("counts a document as filed once it lies in a folder", () => {
    expect(documentProgress(document({ text: "LITTLE" }))).toEqual({ completed: 3, current: "waiting" });
    expect(documentProgress(document({ analysis: "PENDING" }))).toEqual({ completed: 3, current: "running" });
    expect(documentProgress(document({ categoryId: "f1" }))).toEqual({ completed: 4, current: "waiting" });
  });
});

describe("viewerSource", () => {
  it("shows a displayable photo as taken and a HEIC photo as the searchable PDF once archived", () => {
    expect(viewerSource(document({ contentType: "image/jpeg" }))).toEqual({ kind: "image", url: "/api/documents/d1/original?inline=true" });
    expect(viewerSource(document({ contentType: "image/heic" }))).toEqual({ kind: "pdf", url: "/api/documents/d1/archive-pdf?inline=true" });
    expect(viewerSource(document({ contentType: "image/heic", processing: "SUBMITTED" }))).toEqual({ kind: "none", reason: "heic" });
  });

  it("prefers the searchable PDF of scanned pages and falls back to the original", () => {
    expect(viewerSource(document({ archivePdf: true }))).toEqual({ kind: "pdf", url: "/api/documents/d1/archive-pdf?inline=true" });
    expect(viewerSource(document({}))).toEqual({ kind: "pdf", url: "/api/documents/d1/original?inline=true" });
    expect(viewerSource(document({ original: false }))).toEqual({ kind: "none", reason: "unavailable" });
  });
});

describe("thumbnailUrl", () => {
  it("exists only for archived documents", () => {
    expect(thumbnailUrl(document({}))).toBe("/api/documents/d1/thumbnail");
    expect(thumbnailUrl(document({ processing: "SUBMITTED" }))).toBeNull();
  });
});
