import { readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import i18next from "i18next";
import de from "../i18n/de.json";
import en from "../i18n/en.json";
import type { HealthCheck } from "../api/types";
import { CHECK_REASONS, WHAT_NOW_REASONS, areaLabel, checkHint, checkText, lastOkText, problemCount, stateLabel, whatNowText } from "./status";

const i18n = i18next.createInstance();
void i18n.init({ resources: { de: { translation: de }, en: { translation: en } }, lng: "de", fallbackLng: "de" });
const german = i18n.getFixedT("de");
const english = i18n.getFixedT("en");

const check = (overrides: Partial<HealthCheck>): HealthCheck => ({
  area: "database",
  state: "OK",
  reason: "database.connected",
  params: {},
  lastSuccessAt: null,
  ...overrides,
});

/** Every reason code the operations backend puts into a health check (`"area.reason"` literals next to HealthCheck). */
function backendReasons(): Set<string> {
  const root = path.resolve(import.meta.dirname, "../../../backend/operations");
  const files: string[] = [];
  const walk = (directory: string) => {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const full = path.join(directory, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (full.includes(`${path.sep}main${path.sep}`) && entry.name.endsWith(".java")) files.push(full);
    }
  };
  walk(root);
  const reasons = new Set<string>();
  for (const file of files) {
    const source = readFileSync(file, "utf8");
    if (!source.includes("HealthCheck")) continue;
    for (const match of source.matchAll(/"((?:database|archive|backup|export|storage|ai|jobs|probe)\.[a-zA-Z]+)"/g)) {
      reasons.add(match[1]);
    }
  }
  return reasons;
}

describe("status overview", () => {
  it("localizes every reason code the backend sends, in both languages", () => {
    const reasons = backendReasons();
    expect(reasons.size).toBeGreaterThan(15);
    for (const reason of reasons) expect(CHECK_REASONS, reason).toContain(reason);
    for (const reason of CHECK_REASONS) {
      expect(i18n.exists(`status.check.${reason}`, { lng: "de" }), reason).toBe(true);
      expect(i18n.exists(`status.check.${reason}`, { lng: "en" }), reason).toBe(true);
    }
  });

  it("shows an unchecked AI connection as neutral, not as a problem", () => {
    const ai = check({ area: "ai", state: "UNCHECKED", reason: "ai.unchecked" });

    expect(stateLabel(german, ai.state)).toBe("Noch nicht geprüft");
    expect(stateLabel(english, ai.state)).toBe("Not checked yet");
    expect(checkText(german, ai, "de")).toContain("noch nicht geprüft");
    expect(problemCount([ai, check({})])).toBe(0);
    expect(lastOkText(german, ai, "de")).toBeNull();
  });

  it("names a backup problem with its localized reason and what to check, never the code", () => {
    const failed = check({
      area: "backup",
      state: "PROBLEM",
      reason: "backup.failed",
      params: { failureCode: "destination.notMounted" },
      lastSuccessAt: "2026-09-24T01:40:00Z",
    });

    expect(checkText(german, failed, "de")).toBe("Das letzte Backup ist fehlgeschlagen: Die Backup-Platte ist nicht eingebunden.");
    expect(checkText(english, failed, "en")).toBe("The last backup failed: The backup disk is not mounted.");
    expect(checkHint(german, failed)).toContain("USB-Platte");
    expect(checkText(german, failed, "de")).not.toContain("destination.notMounted");
    expect(problemCount([failed, check({})])).toBe(1);
  });

  it("names disks, jobs and AI failures in words and joins several in the viewer's language", () => {
    const low = check({ area: "storage", state: "PROBLEM", reason: "storage.low", params: { disks: "data,backup" } });
    expect(checkText(german, low, "de")).toBe("Der Platz wird knapp: Datenspeicher des Servers und Backup-Platte.");
    expect(checkText(english, low, "en")).toBe("Space is running low: Server data storage and Backup disk.");

    const jobs = check({ area: "jobs", state: "PROBLEM", reason: "jobs.notRunning", params: { jobs: "check-archive-text,future-job" } });
    expect(checkText(german, jobs, "de")).toBe("Läuft nicht wie geplant: Texterkennung prüfen und Hintergrundjob.");

    const ai = check({ area: "ai", state: "PROBLEM", reason: "ai.providerFailure", params: { failure: "AUTHENTICATION" } });
    expect(checkText(german, ai, "de")).toBe("Der letzte Lauf ist gescheitert: Zugangsdaten abgelehnt.");
    const provider = check({ area: "ai", state: "PROBLEM", reason: "ai.credentialsMissing", params: { provider: "something-new" } });
    expect(checkText(english, provider, "en")).toBe("The credentials for the AI provider are missing.");
  });

  it("names the newest export and when the daily export last confirmed it", () => {
    const ok = check({ area: "export", state: "OK", reason: "export.ok", params: { generatedAt: "2026-10-19T00:30:00Z" }, lastSuccessAt: "2026-10-22T00:30:00Z" });
    expect(areaLabel(german, ok.area)).toBe("Täglicher Export");
    expect(checkText(german, ok, "de")).toMatch(/^Aktuell\. Neuester Export: .*19.*, zuletzt geprüft: .*22.*\.$/);
    expect(checkText(english, ok, "en")).toMatch(/^Up to date\. Newest export: .*, last checked: .*\.$/);

    const pending = check({ area: "export", state: "UNCHECKED", reason: "export.pending" });
    expect(checkText(german, pending, "de")).toBe("Der tägliche Export läuft jede Nacht und hat noch nicht stattgefunden. Neuester Export: noch keiner.");
    expect(problemCount([pending])).toBe(0);

    const failed = check({ area: "export", state: "PROBLEM", reason: "export.failed", params: { generatedAt: "2026-10-19T00:30:00Z" } });
    expect(checkText(english, failed, "en")).toMatch(/^The last daily export failed\. Newest complete export: /);
    expect(whatNowText(german, failed)).toContain("letzte vollständige Export bleibt erhalten");
  });

  it("falls back to generic words for an unknown area or reason instead of showing codes", () => {
    const future = check({ area: "calendar", state: "PROBLEM", reason: "calendar.tokenExpired" });

    expect(areaLabel(german, future.area)).toBe("Dienst");
    expect(checkText(german, future, "de")).toBe("Hier stimmt etwas nicht.");
    expect(checkText(english, check({ area: "x", state: "OK", reason: "x.fine" }), "en")).toBe("OK.");
  });

  it("says when a failed area last worked, except for the backup that names its date itself", () => {
    const archive = check({ area: "archive", state: "PROBLEM", reason: "archive.unavailable", lastSuccessAt: "2026-09-26T08:00:00Z" });

    expect(lastOkText(english, archive, "en")).toMatch(/^Last OK: /);
    expect(lastOkText(english, { ...archive, lastSuccessAt: null }, "en")).toBeNull();
    expect(lastOkText(english, { ...archive, area: "backup", reason: "backup.stale" }, "en")).toBeNull();
    expect(lastOkText(english, check({ lastSuccessAt: "2026-09-26T08:00:00Z" }), "en")).toBeNull();
  });
});

describe("what now", () => {
  it("names the backup hint and that documents are safe", () => {
    const failed = check({ area: "backup", state: "PROBLEM", reason: "backup.failed", params: { failureCode: "destination.notMounted" } });
    expect(whatNowText(german, failed)).toBe(
      "Ist die USB-Platte am Server angeschlossen und eingeschaltet? Das nächste Backup läuft heute Nacht um 03:30. Deine Dokumente in Aegis sind davon nicht betroffen.",
    );
  });

  it("has its own text for every other known problem, in both languages", () => {
    for (const reason of WHAT_NOW_REASONS) {
      const problem = check({ area: reason.split(".")[0], state: "PROBLEM", reason });
      for (const translate of [german, english]) {
        const text = whatNowText(translate, problem);
        expect(text, reason).not.toBe(whatNowText(translate, check({ state: "PROBLEM", reason: "unknown.reason" })));
        expect(text, reason).not.toContain("status.");
      }
    }
  });

  it("falls back to a generic text for an unknown reason", () => {
    expect(whatNowText(english, check({ state: "PROBLEM", reason: "unknown.reason" }))).not.toContain("status.");
  });
});
