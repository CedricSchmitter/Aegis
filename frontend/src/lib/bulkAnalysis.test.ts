import { describe, expect, it } from "vitest";
import i18n from "../i18n";
import { doneCount, progressPercent, scopeName, scopeOf, shownRun, skipReason, stopReasonText } from "./bulkAnalysis";
import type { BulkAnalysisDto } from "../api/types";

describe("bulk classification helpers", () => {
  it("turns the picker value into a scope", () => {
    expect(scopeOf("ALL", true)).toEqual({ kind: "ALL", includeSubfolders: false });
    expect(scopeOf("UNSORTED", true)).toEqual({ kind: "UNSORTED", includeSubfolders: false });
    expect(scopeOf("f-1", true)).toEqual({ kind: "FOLDER", folderId: "f-1", includeSubfolders: true });
  });

  it("names scopes, including a folder that was deleted since", async () => {
    await i18n.changeLanguage("en");
    expect(scopeName(i18n.t, { kind: "FOLDER", folderId: "f", includeSubfolders: true, folderPath: ["Haus", "Strom"] }))
      .toBe("Folder Haus / Strom");
    expect(scopeName(i18n.t, { kind: "FOLDER", folderId: "f", includeSubfolders: true, folderPath: [] })).toBe("Deleted folder");
    expect(scopeName(i18n.t, { kind: "UNSORTED", includeSubfolders: false })).toBe("Unsorted");
  });

  it("counts progress from the documents that need nothing more", () => {
    expect(doneCount({ total: 10, pending: 4, inFlight: 2 })).toBe(4);
    expect(progressPercent({ total: 10, pending: 4, inFlight: 2 })).toBe(40);
    expect(progressPercent({ total: 0, pending: 0, inFlight: 0 })).toBe(100);
  });

  it("maps skip codes and stop codes to readable reasons without showing raw codes", async () => {
    await i18n.changeLanguage("de");
    expect(skipReason("document.trashed")).toBe("trashed");
    expect(skipReason("something.new")).toBe("other");
    expect(stopReasonText(i18n.t, "ai.notConfigured")).toBe("Es ist kein KI-Anbieter eingerichtet.");
    expect(stopReasonText(i18n.t, "provider.unavailable")).toBe("der KI-Dienst war vorübergehend nicht erreichbar");
  });

  it("shows the requested run, else the running one, else the newest", () => {
    const runs = [{ id: "a", state: "COMPLETED" }, { id: "b", state: "RUNNING" }] as BulkAnalysisDto[];
    expect(shownRun(runs, "a")?.id).toBe("a");
    expect(shownRun(runs, null)?.id).toBe("b");
    expect(shownRun([runs[0]], "missing")?.id).toBe("a");
    expect(shownRun([], null)).toBeNull();
  });
});
