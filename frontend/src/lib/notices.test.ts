import { describe, expect, it } from "vitest";
import i18next from "i18next";
import de from "../i18n/de.json";
import en from "../i18n/en.json";
import type { NoticeSummary } from "../api/types";
import { noticeText, REVIEW_TITLE_PARAMS } from "./notices";

const notice = (kind: string, params: Record<string, string>): NoticeSummary => ({
  id: "n1",
  kind,
  params,
  linkType: "REVIEW",
  linkId: "c1",
  createdAt: "2026-09-23T10:00:00Z",
  readAt: null,
});

describe("noticeText", () => {
  const i18n = i18next.createInstance();
  void i18n.init({
    resources: { de: { translation: de }, en: { translation: en } },
    lng: "de",
    fallbackLng: "de",
    interpolation: { escapeValue: false },
  });
  const german = i18n.getFixedT("de");
  const english = i18n.getFixedT("en");

  it("names the review card with its reason and the source title in its original language", () => {
    const noText = notice("REVIEW_ITEM", { titleKey: "review.noText", title: "Brief der Stadtwerke" });
    expect(noticeText(german, noText, "de")).toBe("Das Archiv hat in „Brief der Stadtwerke“ keinen oder kaum Text erkannt.");
    expect(noticeText(english, noText, "en")).toBe("The archive recognized no or almost no text in “Brief der Stadtwerke”.");

    const failed = notice("REVIEW_ITEM", { titleKey: "review.analysisFailed", title: "Rechnung", errorCode: "TIMEOUT" });
    expect(noticeText(english, failed, "en")).toBe("Automatic classification failed for “Rechnung” (TIMEOUT).");
  });

  it("shows contract fact types by their localized name", () => {
    const fact = notice("REVIEW_ITEM", {
      titleKey: "review.contractFact",
      contract: "Strom",
      type: "NOTICE_PERIOD",
      value: "P3M",
    });
    expect(noticeText(german, fact, "de")).toBe("Strom: Kündigungsfrist wurde als P3M vorgeschlagen.");
    expect(noticeText(english, fact, "en")).toBe("Strom: Notice period was proposed as P3M.");
  });

  it("keeps the generic text for older notices without the card's parameters and for unknown keys", () => {
    for (const params of [{ titleKey: "review.noText" }, { titleKey: "review.somethingNew", title: "X" }, {}]) {
      expect(noticeText(german, notice("REVIEW_ITEM", params), "de")).toBe("Ein neuer Eintrag muss geprüft werden.");
      expect(noticeText(english, notice("REVIEW_ITEM", params), "en")).toBe("A new item needs review.");
    }
  });

  it("still formats the dates of cancellation reminders", () => {
    const reminder = notice("CONTRACT_CANCELLATION_REMINDER", {
      contractName: "Strom",
      cancellationDeadline: "2026-10-31",
      contractEnd: "2026-12-31",
    });
    expect(noticeText(english, reminder, "en")).toContain("31 Oct 2026");
  });

  it("lists exactly the variables each review title uses in both catalogs", () => {
    for (const [key, required] of Object.entries(REVIEW_TITLE_PARAMS)) {
      for (const catalog of [de, en]) {
        const text = key.split(".").reduce<unknown>((node, part) => (node as Record<string, unknown>)[part], catalog);
        const used = [...String(text).matchAll(/\{\{\s*(\w+)\s*\}\}/g)].map((match) => match[1]).sort();
        expect(used, key).toEqual([...required].sort());
      }
    }
  });
});
