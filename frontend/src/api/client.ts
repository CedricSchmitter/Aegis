import createClient, { type Middleware } from "openapi-fetch";
import type { paths } from "./schema";

/** Problem details as produced by the backend (RFC 9457 plus a stable `code`). */
export interface Problem {
  status?: number;
  code?: string;
  detail?: string;
  title?: string;
  expectedVersion?: number;
  actualVersion?: number;
}

export class ApiError extends Error {
  readonly problem: Problem;
  readonly status: number;

  constructor(status: number, problem: Problem) {
    super(problem.detail ?? problem.code ?? `HTTP ${status}`);
    this.status = status;
    this.problem = { ...problem, status };
  }

  get code(): string {
    return this.problem.code ?? (this.status === 0 ? "network" : "generic");
  }
}

const UNAUTHENTICATED_EVENT = "aegis:unauthenticated";

export function onUnauthenticated(handler: () => void): () => void {
  window.addEventListener(UNAUTHENTICATED_EVENT, handler);
  return () => window.removeEventListener(UNAUTHENTICATED_EVENT, handler);
}

function readCookie(name: string): string | null {
  const match = document.cookie.split("; ").find((c) => c.startsWith(name + "="));
  return match ? decodeURIComponent(match.substring(name.length + 1)) : null;
}

const csrfAndSession: Middleware = {
  onRequest({ request }) {
    if (!["GET", "HEAD", "OPTIONS"].includes(request.method)) {
      const token = readCookie("XSRF-TOKEN");
      if (token) request.headers.set("X-XSRF-TOKEN", token);
    }
    request.headers.set("Accept", "application/json, application/problem+json");
    return request;
  },
  onResponse({ response, request }) {
    if (response.status === 401 && !request.url.endsWith("/api/auth/login")) {
      window.dispatchEvent(new Event(UNAUTHENTICATED_EVENT));
    }
    return response;
  },
};

export const api = createClient<paths>({ baseUrl: "", credentials: "same-origin" });
api.use(csrfAndSession);

/** Unwraps an openapi-fetch result into data or a typed ApiError. */
export function unwrap<T>(result: { data?: T; error?: unknown; response: Response }): T {
  if (result.error !== undefined || !result.response.ok) {
    const problem = (typeof result.error === "object" && result.error !== null ? result.error : {}) as Problem;
    throw new ApiError(result.response.status, problem);
  }
  return result.data as T;
}

export function toApiError(error: unknown): ApiError {
  if (error instanceof ApiError) return error;
  if (error instanceof TypeError) return new ApiError(0, { code: "network" });
  return new ApiError(0, { code: "generic", detail: String(error) });
}
