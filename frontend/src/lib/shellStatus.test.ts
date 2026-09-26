import i18next, { type TFunction } from "i18next";
import { beforeAll, describe, expect, it } from "vitest";
import type { HealthCheck } from "../api/types";
import de from "../i18n/de.json";
import en from "../i18n/en.json";
import { statusDetail, statusHeadline, summarizeStatus } from "./shellStatus";

const check = (area: string, state: HealthCheck["state"], lastSuccessAt?: string): HealthCheck =>
  ({ area, state, reason: `${area}.x`, params: {}, lastSuccessAt }) as HealthCheck;

let t: TFunction;
let tEn: TFunction;

beforeAll(async () => {
  const instance = i18next.createInstance();
  await instance.init({ lng: "de", resources: { de: { translation: de }, en: { translation: en } } });
  t = instance.getFixedT("de");
  tEn = instance.getFixedT("en");
});

describe("shell status", () => {
  it("is unknown and grey until the overview has loaded", () => {
    const summary = summarizeStatus(null);
    expect(summary.tone).toBe("unknown");
    expect(statusHeadline(t, summary)).toBe("Status nicht abrufbar");
    expect(statusDetail(t, summary, "de")).toBeNull();
  });

  it("reads OK when nothing is a problem; an unchecked AI connection is neutral", () => {
    const summary = summarizeStatus([
      check("database", "OK"),
      check("backup", "OK", "2026-09-26T01:31:00Z"),
      check("ai", "UNCHECKED"),
    ]);
    expect(summary.tone).toBe("ok");
    expect(statusHeadline(t, summary)).toBe("Alles in Ordnung");
    expect(statusHeadline(tEn, summary)).toBe("All systems OK");
    expect(statusDetail(t, summary, "de")).toMatch(/^Backup 26\.09\.2026/);
  });

  it("names one broken area and counts several", () => {
    const one = summarizeStatus([check("database", "OK"), check("backup", "PROBLEM", "2026-09-25T01:31:00Z")]);
    expect(one.tone).toBe("problem");
    expect(statusHeadline(t, one)).toBe("Problem: Backup");
    expect(statusHeadline(tEn, one)).toBe("Problem: Backup");
    expect(statusDetail(t, one, "de")).toBeNull();

    const two = summarizeStatus([check("archive", "PROBLEM"), check("storage", "PROBLEM")]);
    expect(statusHeadline(t, two)).toBe("2 Bereiche brauchen Aufmerksamkeit");
    expect(statusHeadline(tEn, two)).toBe("2 areas need attention");
    expect(statusDetail(t, two, "de")).toBe("Archiv (Paperless) und Speicherplatz");
  });
});
