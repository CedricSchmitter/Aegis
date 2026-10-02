import { ApiError, notifyUnauthenticated, readCookie } from "./client";
import type { components } from "./schema";

export type PageEditRequestDto = components["schemas"]["PageEditRequestDto"];
export type PageEditDto = components["schemas"]["PageEditDto"];

/**
 * Requests a page edit (ADR-0024): the plan as JSON and, when it uses new pages, their PDF. Resolves once the server
 * stored both durably; repeating the same plan under the same id returns the existing operation. The multipart body
 * is sent with fetch directly because the generated client does not serialize files.
 */
export async function requestPageEdit(plan: PageEditRequestDto, scan: File | null): Promise<PageEditDto> {
  const form = new FormData();
  form.append("plan", new Blob([JSON.stringify(plan)], { type: "application/json" }), "plan.json");
  if (scan) form.append("scan", scan, scan.name);
  const response = await fetch("/api/page-edits", {
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
  return body as PageEditDto;
}
