import { describe, expect, it } from "vitest";
import { ApiError } from "../api/client";
import type { UploadResponse } from "../api/types";
import {
  MAX_UPLOAD_BYTES,
  contentHead,
  initialStatus,
  isStored,
  isUnauthenticated,
  needsUpload,
  statusFromError,
  statusFromResponse,
} from "./sharedUpload";

const shared = (name: string, type: string, size: number) => ({ key: "s/0", name, type, size, receivedAt: "" });

describe("shared upload rules", () => {
  it("shows the first bytes of a file so an unexpected format can be identified", async () => {
    expect(await contentHead(new Blob(["%PDF-1.7 ..."]))).toBe("25 50 44 46 2d 31 2e 37");
    expect(await contentHead(new Blob([]))).toBe("");
  });

  it("accepts PDFs and photos under any name or type and rejects other, empty or oversized files locally", () => {
    expect(initialStatus(shared("scan.pdf", "application/pdf", 10), "pdf")).toEqual({ kind: "pending" });
    expect(initialStatus(shared("1000012345", "application/octet-stream", 10), "pdf")).toEqual({ kind: "pending" });
    expect(initialStatus(shared("IMG_1.jpg", "image/jpeg", 10), "jpeg")).toEqual({ kind: "pending" });
    expect(initialStatus(shared("", "", 10), "heic")).toEqual({ kind: "pending" });
    expect(initialStatus(shared("fake.pdf", "application/pdf", 10), null)).toEqual({
      kind: "rejected",
      code: "document.unsupportedType",
    });
    expect(initialStatus(shared("clip.gif", "image/gif", 10), null)).toEqual({
      kind: "rejected",
      code: "document.unsupportedType",
    });
    expect(initialStatus(shared("empty.pdf", "application/pdf", 0), null)).toEqual({
      kind: "rejected",
      code: "document.empty",
    });
    expect(initialStatus(shared("big.jpg", "image/jpeg", MAX_UPLOAD_BYTES + 1), "jpeg")).toEqual({
      kind: "rejected",
      code: "document.tooLarge",
    });
    expect(initialStatus(shared("max.pdf", "application/pdf", MAX_UPLOAD_BYTES), "pdf")).toEqual({ kind: "pending" });
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
    const unsupported = statusFromError(new ApiError(400, { code: "document.unsupportedType" }));

    expect(network).toEqual({ kind: "failed", code: "network" });
    expect(server).toEqual({ kind: "failed", code: "paperless.unavailable" });
    expect(csrf).toEqual({ kind: "failed", code: "csrf.invalid" });
    expect(unsupported).toEqual({ kind: "rejected", code: "document.unsupportedType" });
    expect([network, server, csrf].every(needsUpload)).toBe(true);
    expect(needsUpload(unsupported)).toBe(false);
  });

  it("recognizes an expired session so the files wait for the next login", () => {
    expect(isUnauthenticated(new ApiError(401, { code: "auth.required" }))).toBe(true);
    expect(isUnauthenticated(new ApiError(403, { code: "auth.forbidden" }))).toBe(false);
  });
});
