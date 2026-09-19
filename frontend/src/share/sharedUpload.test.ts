import { describe, expect, it } from "vitest";
import { ApiError } from "../api/client";
import type { UploadResponse } from "../api/types";
import {
  MAX_UPLOAD_BYTES,
  hasPdfSignature,
  initialStatus,
  isStored,
  isUnauthenticated,
  needsUpload,
  statusFromError,
  statusFromResponse,
  uploadName,
} from "./sharedUpload";

const shared = (name: string, type: string, size: number) => ({ key: "s/0", name, type, size, receivedAt: "" });

describe("shared upload rules", () => {
  it("recognizes PDFs by their content, whatever name and type the sharing app reports", async () => {
    expect(await hasPdfSignature(new Blob(["%PDF-1.7 ..."]))).toBe(true);
    expect(await hasPdfSignature(new Blob(["ÿØÿ JPEG"]))).toBe(false);
    expect(await hasPdfSignature(new Blob(["%PD"]))).toBe(false);
    expect(await hasPdfSignature(new Blob([]))).toBe(false);
  });

  it("accepts PDF content under any name or type and rejects other, empty or oversized files locally", () => {
    expect(initialStatus(shared("scan.pdf", "application/pdf", 10), true)).toEqual({ kind: "pending" });
    expect(initialStatus(shared("1000012345", "application/octet-stream", 10), true)).toEqual({ kind: "pending" });
    expect(initialStatus(shared("", "", 10), true)).toEqual({ kind: "pending" });
    expect(initialStatus(shared("fake.pdf", "application/pdf", 10), false)).toEqual({
      kind: "rejected",
      code: "document.notPdf",
    });
    expect(initialStatus(shared("empty.pdf", "application/pdf", 0), false)).toEqual({
      kind: "rejected",
      code: "document.empty",
    });
    expect(initialStatus(shared("big.pdf", "application/pdf", MAX_UPLOAD_BYTES + 1), true)).toEqual({
      kind: "rejected",
      code: "document.tooLarge",
    });
    expect(initialStatus(shared("max.pdf", "application/pdf", MAX_UPLOAD_BYTES), true)).toEqual({ kind: "pending" });
  });

  it("gives the server a PDF filename even when the source app sent none", () => {
    expect(uploadName("Rechnung.pdf", "Geteilt")).toBe("Rechnung.pdf");
    expect(uploadName("Rechnung.PDF", "Geteilt")).toBe("Rechnung.PDF");
    expect(uploadName("document-1234", "Geteilt")).toBe("document-1234.pdf");
    expect(uploadName("  ", "Geteilt 2026-09-19")).toBe("Geteilt 2026-09-19.pdf");
  });

  it("claims storage only for confirmed server answers", () => {
    const response = (duplicate: boolean) =>
      ({ document: { id: "d1", title: "Rechnung" }, duplicate }) as unknown as UploadResponse;
    const saved = statusFromResponse(response(false));
    const duplicate = statusFromResponse(response(true));

    expect(saved).toEqual({ kind: "saved", documentId: "d1", title: "Rechnung" });
    expect(duplicate).toEqual({ kind: "duplicate", documentId: "d1", title: "Rechnung" });
    expect(isStored(saved) && isStored(duplicate)).toBe(true);
    expect(isStored({ kind: "uploading" })).toBe(false);
    expect(isStored({ kind: "failed", code: "network" })).toBe(false);
  });

  it("offers a retry for interrupted or failed uploads but not for final validation answers", () => {
    const network = statusFromError(new TypeError("Failed to fetch"));
    const server = statusFromError(new ApiError(503, { code: "paperless.unavailable" }));
    const csrf = statusFromError(new ApiError(403, { code: "csrf.invalid" }));
    const notPdf = statusFromError(new ApiError(400, { code: "document.notPdf" }));

    expect(network).toEqual({ kind: "failed", code: "network" });
    expect(server).toEqual({ kind: "failed", code: "paperless.unavailable" });
    expect(csrf).toEqual({ kind: "failed", code: "csrf.invalid" });
    expect(notPdf).toEqual({ kind: "rejected", code: "document.notPdf" });
    expect([network, server, csrf].every(needsUpload)).toBe(true);
    expect(needsUpload(notPdf)).toBe(false);
  });

  it("recognizes an expired session so the files wait for the next login", () => {
    expect(isUnauthenticated(new ApiError(401, { code: "auth.required" }))).toBe(true);
    expect(isUnauthenticated(new ApiError(403, { code: "auth.forbidden" }))).toBe(false);
  });
});
