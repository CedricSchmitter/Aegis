import { toApiError } from "../api/client";
import type { UploadResponse } from "../api/types";
import { localRejection, type UploadFormat } from "../lib/uploadFormat";
import type { SharedFile } from "./inbox";

export { MAX_UPLOAD_BYTES } from "../lib/uploadFormat";

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

/** First bytes as hex, shown with a rejected file so an unexpected format can be identified. */
export async function contentHead(content: Blob, length = 8): Promise<string> {
  const head = new Uint8Array(await content.slice(0, length).arrayBuffer());
  return [...head].map((byte) => byte.toString(16).padStart(2, "0")).join(" ");
}

export function initialStatus(file: SharedFile, format: UploadFormat | null): ShareStatus {
  const code = localRejection(file, format);
  return code ? { kind: "rejected", code } : { kind: "pending" };
}

export function statusFromResponse(response: UploadResponse): ShareStatus {
  const { id, title } = response.document;
  return response.duplicate ? { kind: "duplicate", documentId: id, title } : { kind: "saved", documentId: id, title };
}

/** Codes of server answers that will not change when the same file is sent again. */
const PERMANENT = /^(document\.(unsupportedType|tooLarge|empty|filename\..+)|validation\.failed)$/;

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
