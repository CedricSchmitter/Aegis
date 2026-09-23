import { readFileSync, readdirSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { failureReason } from "./failureReasons";

/**
 * Every failure code the document review producer can publish today: `lastErrorCode` of a failed archival
 * (ArchiveDocumentTask, Paperless adapter) and `failureCode` of a failed or unavailable analysis (RunFailure codes of
 * the assistant context, DocumentAnalysis.unavailableCode and the `ai.run.*` codes AnalysisViews builds).
 */
const PUBLISHED_CODES = [
  "paperless.unavailable", "paperless.timeout", "paperless.rejected", "paperless.consumptionFailed",
  "paperless.revoked", "paperless.noTaskId", "paperless.noDocumentId", "document.stagingMissing",
  "ai.notConfigured", "ai.workload.unavailable", "ai.workload.notImplemented", "ai.price.unknown",
  "ai.provider.notConfigured", "ai.provider.memberNotAllowed", "auth.forbidden",
  "provider.unavailable", "provider.unreachable", "provider.overloaded", "provider.busy", "provider.timeout",
  "provider.rateLimited", "provider.cliUnavailable", "provider.error",
  "provider.authentication", "provider.permission", "provider.billing", "provider.modelNotFound",
  "provider.invalidRequest", "provider.requestTooLarge", "provider.toolsUnsupported",
  "provider.conversationUnsupported", "tool.notGranted",
  "budget.cost", "budget.daily", "budget.time", "budget.modelCalls", "budget.toolCalls",
  "output.schemaMismatch", "output.truncated", "output.unexpectedStop", "output.tooLarge", "output.missingToolCall",
  "provider.invalidResponse", "model.refused",
  "source.changed", "source.missing", "source.unavailable",
  "attempt.interrupted", "ai.run.cancelled", "ai.run.failed", "ai.run.missing",
];

/** Unexpected defects and the producer's placeholder for a missing code deliberately get the generic reason. */
const GENERIC_CODES = ["internal.error", "unknown"];

function javaSources(directory: string): string[] {
  return readdirSync(directory, { recursive: true, encoding: "utf8" })
    .filter((file) => file.endsWith(".java") && file.includes(`${path.sep}main${path.sep}`))
    .map((file) => path.join(directory, file));
}

/** Failure codes as they are written in the backend: failure categories, archive failures and output errors. */
function backendFailureCodes(): Set<string> {
  const backend = path.resolve(import.meta.dirname, "../../../backend");
  const patterns = [
    /FailureCategory\.[A-Z_]+,\s*(?:\w+\s*\?\s*"([\w.]+)"\s*:\s*)?"([\w.]+)"/g,
    /new (?:Rejected|Failed|InvalidModelOutputException)\(\s*"([\w.]+)"/g,
    /recordFailure\([^,()]+,\s*"([\w.]+)"/g,
  ];
  const codes = new Set<string>();
  for (const file of ["assistant", "documents"].flatMap((context) => javaSources(path.join(backend, context)))) {
    const source = readFileSync(file, "utf8");
    for (const pattern of patterns) {
      for (const match of source.matchAll(pattern)) {
        for (const code of match.slice(1)) if (code) codes.add(code);
      }
    }
  }
  return codes;
}

describe("failureReason", () => {
  it("names every code the review producer publishes with a specific reason", () => {
    for (const code of PUBLISHED_CODES) {
      expect(failureReason(code), code).not.toBe("unknown");
    }
  });

  it("knows every failure code written in the backend", () => {
    const codes = backendFailureCodes();
    expect(codes.size).toBeGreaterThan(30);
    for (const code of codes) {
      expect([...PUBLISHED_CODES, ...GENERIC_CODES], code).toContain(code);
    }
  });

  it("groups codes by what a member can do about them", () => {
    expect(failureReason("paperless.timeout")).toBe("archiveUnreachable");
    expect(failureReason("paperless.consumptionFailed")).toBe("archiveRejected");
    expect(failureReason("ai.provider.memberNotAllowed")).toBe("aiMemberNotAllowed");
    expect(failureReason("provider.rateLimited")).toBe("aiUnavailable");
    expect(failureReason("budget.daily")).toBe("aiLimitReached");
    expect(failureReason("output.somethingNew")).toBe("aiOutputUnusable");
    expect(failureReason("ai.run.cancelled")).toBe("interrupted");
  });

  it("falls back to the generic reason for unknown, missing and unexpected codes", () => {
    for (const code of [...GENERIC_CODES, "paperless.somethingNew", "provider.somethingNew", "", null, undefined]) {
      expect(failureReason(code), String(code)).toBe("unknown");
    }
  });
});
