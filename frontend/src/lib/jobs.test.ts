import { describe, expect, it } from "vitest";
import i18next from "i18next";
import de from "../i18n/de.json";
import en from "../i18n/en.json";
import { jobInterval } from "./jobs";

describe("jobInterval", () => {
  const i18n = i18next.createInstance();
  void i18n.init({ resources: { de: { translation: de }, en: { translation: en } }, lng: "de", fallbackLng: "de" });

  it("names the interval in the largest whole unit in both languages", () => {
    const german = i18n.getFixedT("de");
    const english = i18n.getFixedT("en");
    expect(jobInterval(german, 60)).toBe("jede Minute");
    expect(jobInterval(german, 300)).toBe("alle 5 Minuten");
    expect(jobInterval(german, 86_400)).toBe("täglich");
    expect(jobInterval(german, 43_200)).toBe("alle 12 Stunden");
    expect(jobInterval(english, 60)).toBe("every minute");
    expect(jobInterval(english, 300)).toBe("every 5 minutes");
    expect(jobInterval(english, 3_600)).toBe("hourly");
    expect(jobInterval(english, 172_800)).toBe("every 2 days");
    expect(jobInterval(english, 90)).toBe("every 2 minutes");
  });
});
