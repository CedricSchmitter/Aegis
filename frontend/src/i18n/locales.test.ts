import { readFileSync, readdirSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import de from "./de.json";
import en from "./en.json";
import { FAILURE_REASONS } from "../lib/failureReasons";

/** All TypeScript sources of the app, so no translation key is missed. */
function collectSources(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(directory, entry.name);
    if (entry.isDirectory()) return collectSources(full);
    return /\.tsx?$/.test(entry.name) && !entry.name.endsWith(".test.ts") ? [full] : [];
  });
}

type Tree = { [key: string]: string | Tree };

function flatten(tree: Tree, prefix = ""): Map<string, string> {
  const out = new Map<string, string>();
  for (const [key, value] of Object.entries(tree)) {
    const path = prefix ? `${prefix}.${key}` : key;
    if (typeof value === "string") out.set(path, value);
    else for (const [k, v] of flatten(value, path)) out.set(k, v);
  }
  return out;
}

function variables(text: string): string[] {
  return [...text.matchAll(/\{\{\s*([\w.]+)\s*\}\}/g)].map((m) => m[1]).sort();
}

describe("locale catalogs", () => {
  const german = flatten(de as Tree);
  const english = flatten(en as Tree);

  it("contain exactly the same keys", () => {
    expect([...german.keys()].sort()).toEqual([...english.keys()].sort());
  });

  it("use the same interpolation variables per key", () => {
    for (const [key, text] of german) {
      expect(variables(text), key).toEqual(variables(english.get(key) ?? ""));
    }
  });

  it("have no empty or key-like values", () => {
    for (const catalog of [german, english]) {
      for (const [key, text] of catalog) {
        expect(text.trim(), key).not.toBe("");
        expect(text, key).not.toMatch(/^[a-z]+(\.[a-zA-Z_]+)+$/);
      }
    }
  });

  it("provide every key the source uses statically", () => {
    const sources = collectSources(path.resolve(import.meta.dirname, ".."));
    const used = new Set<string>();
    for (const file of sources) {
      for (const match of readFileSync(file, "utf8").matchAll(/\bt\(\s*"([a-zA-Z0-9_.]+)"/g)) {
        used.add(match[1]);
      }
    }

    expect(used.size).toBeGreaterThan(50);
    for (const key of used) {
      // Error codes come from the API and fall back to a generic message on purpose.
      if (key.startsWith("errors.")) continue;
      // A trailing dot is the prefix of a key that is completed at runtime.
      if (key.endsWith(".")) continue;
      expect(german.has(key), key).toBe(true);
    }
  });

  it("cover the key families that are built at runtime", () => {
    const families = [
      ...["DOCUMENT_TYPE", "ORGANIZATION", "TOPIC", "TAG", "SUBJECT"].flatMap((facet) => [
        `facets.${facet}`,
        `facets.hint${facet}`,
      ]),
      ...["NEWEST", "OLDEST", "TITLE"].map((order) => `documents.sort${order}`),
      ...["ACTIVE", "RETIRED", "MERGED"].map((status) => `labels.status.${status}`),
      ...["house", "invoices", "internet", "water", "insurance"].map((key) => `folders.structure.${key}`),
      ...["HOUSE", "MEMBER"].map((kind) => `subjects.${kind}`),
      ...["pending", "uploading", "saved", "duplicate", "failed", "rejected"].map((s) => `share.status.${s}`),
      ...["CONNECTION_CHECK", "DOCUMENT_CLASSIFICATION", "DOCUMENT_REVIEW", "CHAT"].map((w) => `ai.workload.${w}`),
      ...["QUEUED", "RUNNING", "WAITING_RETRY", "SUCCEEDED", "FAILED", "CANCELLED"].map((s) => `ai.state.${s}`),
      ...["NOT_CONFIGURED", "AUTHENTICATION", "MODEL_UNAVAILABLE", "INVALID_REQUEST", "RATE_LIMITED",
        "PROVIDER_UNAVAILABLE", "TIMEOUT", "INVALID_OUTPUT", "REFUSED", "BUDGET_EXHAUSTED", "TOOL_DENIED",
        "NOT_AUTHORIZED", "STALE_SOURCE", "SOURCE_UNAVAILABLE", "INTERRUPTED", "INTERNAL"].map((c) => `ai.failure.${c}`),
      ...["Y", "M", "D"].map((unit) => `contracts.periodUnit.${unit}`),
      ...["NONE", "LITTLE"].map((state) => `documents.text.badge.${state}`),
      // Recurring background jobs on the status page (ADR-0013).
      ...["OK", "PENDING", "FAILING", "STALE", "NOT_SCHEDULED", "UNKNOWN"].map((s) => `status.jobs.state.${s}`),
      ...["reconcile-review-inbox", "check-archive-text", "reconcile-documents"].flatMap((job) => [
        `status.jobs.name.${job}`,
        `status.jobs.purpose.${job}`,
      ]),
      ...["minutes", "hours", "days"].flatMap((unit) => [`status.jobs.every.${unit}`, `status.jobs.every.${unit}_one`]),
      // Keys the document review producer publishes for documents without recognized text (ADR-0012).
      "review.noText",
      "review.reason.NO_TEXT_RECOGNIZED",
      // Short reasons for the failure codes of review cards and archival notices (issue #96).
      ...FAILURE_REASONS.map((reason) => `review.failureReason.${reason}`),
    ];

    for (const key of families) {
      expect(german.has(key), key).toBe(true);
      expect(english.has(key), key).toBe(true);
    }
  });

  it("translate every built-in label key of the backend migration", () => {
    const migration = readFileSync(
      path.resolve(import.meta.dirname, "../../../backend/documents/adapters/src/main/resources/db/migration",
        "documents/V2_2__document_catalog.sql"),
      "utf8",
    );
    const keys = [...migration.matchAll(/'[0-9a-f-]{36}', '(?:DOCUMENT_TYPE|TOPIC)', '([A-Za-z]+)'/g)]
      .map((match) => match[1]);

    expect(keys.length).toBeGreaterThan(20);
    for (const key of keys) {
      expect(german.has(`labels.builtin.${key}`), key).toBe(true);
      expect(english.has(`labels.builtin.${key}`), key).toBe(true);
    }
  });

  it("translate every contract enumeration the backend publishes", () => {
    const enumConstants = (file: string) =>
      [...readFileSync(path.resolve(import.meta.dirname, "../../../backend/contracts/domain/src/main/java/dev/aegis",
        "contracts/domain", file), "utf8").matchAll(/^ {4,8}([A-Z][A-Z_]+) *(?:\(|,|;|$)/gm)].map((match) => match[1]);

    const factTypes = enumConstants("FactType.java");
    const reviewStates = enumConstants("ReviewState.java");
    const statuses = enumConstants("ContractStatus.java");
    const conflictKinds = enumConstants("FactConflict.java");
    const bases = enumConstants("deadline/DeadlineBasis.java");

    expect(factTypes.length).toBe(11);
    expect(reviewStates.length).toBe(4);
    expect(statuses.length).toBe(3);
    expect(conflictKinds.length).toBe(2);
    expect(bases.length).toBe(2);

    const keys = [
      ...factTypes.map((type) => `contracts.factType.${type}`),
      ...reviewStates.flatMap((state) => [`contracts.reviewState.${state}`, `contracts.fact.decided.${state}`]),
      ...statuses.map((status) => `contracts.status.${status}`),
      ...conflictKinds.map((kind) => `contracts.conflicts.kind.${kind}`),
      ...bases.map((basis) => `contracts.deadline.basis.${basis}`),
    ];

    for (const key of keys) {
      expect(german.has(key), key).toBe(true);
      expect(english.has(key), key).toBe(true);
    }
  });

  it("cover every processing state, notice kind and integration state", () => {
    for (const state of ["RECEIVED", "SUBMITTED", "ARCHIVED", "FAILED"]) {
      expect(german.has(`documents.state.${state}`)).toBe(true);
    }
    for (const kind of ["TASK_ASSIGNED", "TASK_COMPLETED", "CONTRACT_CANCELLATION_REMINDER", "JOB_STALE"]) {
      expect(german.has(`notices.kind.${kind}`)).toBe(true);
    }
    for (const state of ["CONNECTED", "DEGRADED", "UNAVAILABLE", "NOT_CONFIGURED"]) {
      expect(german.has(`status.state.${state}`)).toBe(true);
    }
  });
});
