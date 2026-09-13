import { describe, expect, it } from "vitest";
import de from "./de.json";
import en from "./en.json";

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

  it("cover every processing state, notice kind and integration state", () => {
    for (const state of ["RECEIVED", "SUBMITTED", "ARCHIVED", "FAILED"]) {
      expect(german.has(`documents.state.${state}`)).toBe(true);
    }
    for (const kind of ["TASK_ASSIGNED", "TASK_COMPLETED", "DOCUMENT_ARCHIVED", "DOCUMENT_ARCHIVAL_FAILED"]) {
      expect(german.has(`notices.kind.${kind}`)).toBe(true);
    }
    for (const state of ["CONNECTED", "DEGRADED", "UNAVAILABLE", "NOT_CONFIGURED"]) {
      expect(german.has(`status.state.${state}`)).toBe(true);
    }
  });
});
