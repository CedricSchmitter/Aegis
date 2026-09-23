import { describe, expect, it } from "vitest";
import i18next from "i18next";
import de from "../i18n/de.json";
import en from "../i18n/en.json";
import type { NoticeSummary } from "../api/types";
import { noticeText, REVIEW_TITLE_PARAMS, reviewTitle } from "./notices";

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
  });

  it("shows a failure code as a short localized reason, never the code itself", () => {
    const analysis = notice("REVIEW_ITEM", {
      titleKey: "review.analysisFailed",
      title: "Rechnung",
      errorCode: "ai.provider.memberNotAllowed",
    });
    expect(noticeText(german, analysis, "de")).toBe(
      "Die automatische Einordnung von „Rechnung“ ist fehlgeschlagen: die KI ist für das hochladende Konto nicht freigegeben.",
    );
    expect(noticeText(english, analysis, "en")).toBe(
      "Automatic classification failed for “Rechnung”: AI is not enabled for the uploading account.",
    );

    const archive = notice("REVIEW_ITEM", { titleKey: "review.archiveFailed", title: "Rechnung", errorCode: "paperless.timeout" });
    expect(noticeText(german, archive, "de")).toBe("Die Archivierung von „Rechnung“ ist fehlgeschlagen: das Archiv war nicht erreichbar.");
    expect(noticeText(english, archive, "en")).toBe("Archival failed for “Rechnung”: the archive could not be reached.");

    const unknown = notice("REVIEW_ITEM", { titleKey: "review.archiveFailed", title: "Rechnung", errorCode: "paperless.new" });
    expect(noticeText(german, unknown, "de")).toBe("Die Archivierung von „Rechnung“ ist fehlgeschlagen: ein unerwarteter Fehler ist aufgetreten.");
    expect(noticeText(english, unknown, "en")).toBe("Archival failed for “Rechnung”: an unexpected error occurred.");
  });

  it("shows the reason of a failed archival in the uploader's notice", () => {
    const failed = notice("DOCUMENT_ARCHIVAL_FAILED", { title: "Rechnung", code: "paperless.rejected" });
    expect(noticeText(german, failed, "de")).toBe(
      "Die Archivierung von „Rechnung“ ist fehlgeschlagen: das Archiv konnte die Datei nicht verarbeiten. Das Original bleibt gespeichert.",
    );
    expect(noticeText(english, failed, "en")).toBe(
      "Archival of “Rechnung” failed: the archive could not process the file. The original remains stored.",
    );
  });

  it("keeps an archival notice readable when its code is missing", () => {
    const failed = notice("DOCUMENT_ARCHIVAL_FAILED", { title: "Rechnung" });
    expect(noticeText(english, failed, "en")).toBe(
      "Archival of “Rechnung” failed: an unexpected error occurred. The original remains stored.",
    );
  });

  it("renders review cards and notices from their stored codes in both languages", () => {
    const texts = [
      reviewTitle(german, "review.analysisFailed", { title: "X", errorCode: "budget.daily" }),
      reviewTitle(english, "review.archiveFailed", { title: "X", errorCode: "document.stagingMissing" }),
      noticeText(english, notice("DOCUMENT_ARCHIVAL_FAILED", { title: "X", code: "paperless.unavailable" }), "en"),
    ];
    for (const text of texts) {
      expect(text).not.toMatch(/\{\{|budget\.|document\.|paperless\./);
    }
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
    const older: Record<string, string>[] = [
      { titleKey: "review.noText" },
      { titleKey: "review.analysisFailed", title: "X" },
      { titleKey: "review.somethingNew", title: "X" },
      {},
    ];
    for (const params of older) {
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
      // The stored failure code is presented as the derived variable `reason`.
      const variables = required.map((name) => (name === "errorCode" ? "reason" : name)).sort();
      for (const catalog of [de, en]) {
        const text = key.split(".").reduce<unknown>((node, part) => (node as Record<string, unknown>)[part], catalog);
        const used = [...String(text).matchAll(/\{\{\s*(\w+)\s*\}\}/g)].map((match) => match[1]).sort();
        expect(used, key).toEqual(variables);
      }
    }
  });
});
