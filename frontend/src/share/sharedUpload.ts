import { toApiError } from "../api/client";
import type { UploadResponse } from "../api/types";
import type { SharedFile } from "./inbox";

/** Matches aegis.archival.max-upload-bytes; the server checks again, this only gives an early, clear answer. */
export const MAX_UPLOAD_BYTES = 50 * 1024 * 1024;

/**
 * State of one shared file on the share page. Only `saved` and `duplicate` mean the server confirmed durable
 * storage; `rejected` cannot succeed by retrying, `failed` can.
 */
export type ShareStatus =
  | { kind: "pending" }
  | { kind: "uploading" }
  | { kind: "saved"; documentId: string; title: string }
  | { kind: "duplicate"; documentId: string; title: string }
  | { kind: "failed"; code: string }
  | { kind: "rejected"; code: string };

export function isPdf(file: Pick<SharedFile, "name" | "type">): boolean {
  return file.type === "application/pdf" || file.name.trim().toLowerCase().endsWith(".pdf");
}

/** Local check before anything is sent; returns the same error codes the server uses. */
export function localRejection(file: Pick<SharedFile, "name" | "type" | "size">): string | null {
  if (!isPdf(file)) return "document.notPdf";
  if (file.size === 0) return "document.empty";
  if (file.size > MAX_UPLOAD_BYTES) return "document.tooLarge";
  return null;
}

export function initialStatus(file: SharedFile): ShareStatus {
  const code = localRejection(file);
  return code ? { kind: "rejected", code } : { kind: "pending" };
}

/**
 * Filename sent to the server. Some apps share a PDF without a name or without the extension the server requires;
 * the server still checks the PDF signature, so the extension only makes a correctly typed file acceptable.
 */
export function uploadName(name: string, fallbackBase: string): string {
  const trimmed = name.trim();
  const base = trimmed === "" ? fallbackBase : trimmed;
  return base.toLowerCase().endsWith(".pdf") ? base : `${base}.pdf`;
}

export function statusFromResponse(response: UploadResponse): ShareStatus {
  const { id, title } = response.document;
  return response.duplicate ? { kind: "duplicate", documentId: id, title } : { kind: "saved", documentId: id, title };
}

/** Codes of server answers that will not change when the same file is sent again. */
const PERMANENT = /^(document\.(notPdf|tooLarge|empty|filename\..+)|validation\.failed)$/;

/** Maps a failed upload: validation answers are final, everything else (network, server, session) can be retried. */
export function statusFromError(error: unknown): ShareStatus {
  const apiError = toApiError(error);
  const code = apiError.code;
  if (apiError.status >= 400 && apiError.status < 500 && PERMANENT.test(code)) return { kind: "rejected", code };
  return { kind: "failed", code };
}

export function isUnauthenticated(error: unknown): boolean {
  return toApiError(error).status === 401;
}

/** Files that the save action still has to send. */
export function needsUpload(status: ShareStatus): boolean {
  return status.kind === "pending" || status.kind === "failed";
}

export function isStored(status: ShareStatus): boolean {
  return status.kind === "saved" || status.kind === "duplicate";
}
