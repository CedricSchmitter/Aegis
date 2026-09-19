import { ApiError, notifyUnauthenticated, readCookie } from "./client";
import type { UploadResponse } from "./types";

export interface UploadOptions {
  subjects: string[];
  categoryId: string | null;
}

/**
 * Uploads one original through the durable capture endpoint. Resolves only after the server confirmed durable
 * storage (or recognized an existing identical file); every other outcome throws an ApiError. The multipart body is
 * sent with fetch directly because the generated client does not serialize files.
 */
export async function uploadDocument(file: File, options: UploadOptions): Promise<UploadResponse> {
  const form = new FormData();
  form.append("file", file, file.name);
  options.subjects.forEach((subject) => form.append("subjects", subject));
  const target = options.categoryId ? `?categoryId=${encodeURIComponent(options.categoryId)}` : "";
  const response = await fetch(`/api/documents${target}`, {
    method: "POST",
    body: form,
    credentials: "same-origin",
    headers: {
      Accept: "application/json, application/problem+json",
      "X-XSRF-TOKEN": readCookie("XSRF-TOKEN") ?? "",
    },
  });
  const body: unknown = await response.json().catch(() => ({}));
  if (!response.ok) {
    if (response.status === 401) notifyUnauthenticated();
    throw new ApiError(response.status, typeof body === "object" && body !== null ? body : {});
  }
  return body as UploadResponse;
}
