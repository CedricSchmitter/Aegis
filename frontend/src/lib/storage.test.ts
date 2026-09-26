import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import i18next from "i18next";
import de from "../i18n/de.json";
import en from "../i18n/en.json";
import { BACKUP_REASONS, backupReason, backupReasonText, diskUsageText, usedPercent } from "./storage";
import type { DiskSpace } from "./storage";

/**
 * Every failure code the backup script can record (`BackupFailure("...")` and `step("...", ...)` in
 * ops/backup/backup.py) plus the application's own code for an unreadable state file.
 */
function recordedCodes(): Set<string> {
  const root = path.resolve(import.meta.dirname, "../../..");
  const script = readFileSync(path.join(root, "ops/backup/backup.py"), "utf8");
  const codes = new Set<string>();
  for (const match of script.matchAll(/(?:BackupFailure|step)\(\s*"([\w.]+)"/g)) codes.add(match[1]);
  const health = readFileSync(
    path.join(root, "backend/operations/application/src/main/java/dev/aegis/operations/application/api/BackupHealth.java"),
    "utf8",
  );
  for (const match of health.matchAll(/STATE_UNREADABLE = "([\w.]+)"/g)) codes.add(match[1]);
  return codes;
}

const disk = (overrides: Partial<DiskSpace>): DiskSpace => ({
  name: "data",
  state: "OK",
  totalBytes: 100,
  freeBytes: 25,
  lowBelowBytes: 10,
  measuredAt: "2026-09-24T12:00:00Z",
  ...overrides,
});

describe("backup reasons", () => {
  it("names every code the backup script records with a specific reason", () => {
    const codes = recordedCodes();
    expect(codes.size).toBeGreaterThanOrEqual(10);
    for (const code of codes) expect(backupReason(code), code).not.toBe("unknown");
  });

  it("falls back to the generic reason, never the raw code", () => {
    expect(backupReason("unexpected")).toBe("unknown");
    expect(backupReason("unknown")).toBe("unknown");
    expect(backupReason(null)).toBe("unknown");
  });

  it("has a reason and a hint for every reason in both catalogs", () => {
    for (const catalog of [de, en]) {
      expect(Object.keys(catalog.status.backup.reason).sort()).toEqual([...BACKUP_REASONS].sort());
      expect(Object.keys(catalog.status.backup.hint).sort()).toEqual([...BACKUP_REASONS].sort());
    }
  });

  it("shows the localized reason", () => {
    const i18n = i18next.createInstance();
    void i18n.init({ resources: { de: { translation: de }, en: { translation: en } }, lng: "de", interpolation: { escapeValue: false } });
    expect(backupReasonText(i18n.getFixedT("de"), "destination.notMounted")).toBe("Die Backup-Platte ist nicht eingebunden.");
    expect(backupReasonText(i18n.getFixedT("en"), "check.failed")).toBe("The check of the backup archive found errors.");
    expect(diskUsageText(i18n.getFixedT("de"), disk({ totalBytes: 93 * 1024 ** 3, freeBytes: 64 * 1024 ** 3 }), "de")).toBe(
      "64 GB frei von 93 GB",
    );
    expect(diskUsageText(i18n.getFixedT("en"), disk({ totalBytes: null, freeBytes: null, state: "UNKNOWN" }), "en")).toBe(
      "Could not be measured.",
    );
  });
});

describe("storage state", () => {
  it("computes the used share of a measured disk only", () => {
    expect(usedPercent(disk({}))).toBe(75);
    expect(usedPercent(disk({ freeBytes: 0 }))).toBe(100);
    expect(usedPercent(disk({ totalBytes: null, freeBytes: null }))).toBeNull();
    expect(usedPercent(disk({ totalBytes: 0, freeBytes: 0 }))).toBeNull();
  });
});
