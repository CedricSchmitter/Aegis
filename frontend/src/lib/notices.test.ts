import { describe, expect, it } from "vitest";
import i18next from "i18next";
import de from "../i18n/de.json";
import en from "../i18n/en.json";
import type { NoticeSummary } from "../api/types";
import { formatDateTime } from "./format";
import { noticeText, REVIEW_TITLE_PARAMS, reviewTitle } from "./notices";

const notice = (kind: string, params: Record<string, string>): NoticeSummary => ({
  id: "n1",
  kind,
  params,
  linkType: "STATUS",
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
    const params = { title: "Brief der Stadtwerke" };
    expect(reviewTitle(german, "review.noText", params)).toBe("Das Archiv hat in „Brief der Stadtwerke“ keinen oder kaum Text erkannt.");
    expect(reviewTitle(english, "review.noText", params)).toBe("The archive recognized no or almost no text in “Brief der Stadtwerke”.");
  });

  it("shows a failure code as a short localized reason, never the code itself", () => {
    const analysis = { title: "Rechnung", errorCode: "ai.provider.memberNotAllowed" };
    expect(reviewTitle(german, "review.analysisFailed", analysis)).toBe(
      "Die automatische Einordnung von „Rechnung“ ist fehlgeschlagen: die KI ist für das hochladende Konto nicht freigegeben.",
    );
    expect(reviewTitle(english, "review.analysisFailed", analysis)).toBe(
      "Automatic classification failed for “Rechnung”: AI is not enabled for the uploading account.",
    );

    const archive = { title: "Rechnung", errorCode: "paperless.timeout" };
    expect(reviewTitle(german, "review.archiveFailed", archive)).toBe("Die Archivierung von „Rechnung“ ist fehlgeschlagen: das Archiv war nicht erreichbar.");
    expect(reviewTitle(english, "review.archiveFailed", archive)).toBe("Archival failed for “Rechnung”: the archive could not be reached.");

    const unknown = { title: "Rechnung", errorCode: "paperless.new" };
    expect(reviewTitle(german, "review.archiveFailed", unknown)).toBe("Die Archivierung von „Rechnung“ ist fehlgeschlagen: ein unerwarteter Fehler ist aufgetreten.");
    expect(reviewTitle(english, "review.archiveFailed", unknown)).toBe("Archival failed for “Rechnung”: an unexpected error occurred.");
  });

  it("renders review cards from their stored codes in both languages", () => {
    const texts = [
      reviewTitle(german, "review.analysisFailed", { title: "X", errorCode: "budget.daily" }),
      reviewTitle(english, "review.archiveFailed", { title: "X", errorCode: "document.stagingMissing" }),
    ];
    for (const text of texts) {
      expect(text).not.toMatch(/\{\{|budget\.|document\.|paperless\./);
    }
  });

  it("shows contract fact types by their localized name", () => {
    const fact = { contract: "Strom", type: "NOTICE_PERIOD", value: "P3M" };
    expect(reviewTitle(german, "review.contractFact", fact)).toBe("Strom: Kündigungsfrist wurde als P3M vorgeschlagen.");
    expect(reviewTitle(english, "review.contractFact", fact)).toBe("Strom: Notice period was proposed as P3M.");
  });

  it("has texts only for the kinds the inbox lists as messages", () => {
    // Card notices are shown as their card and the archived notice is retired (issue #83).
    for (const catalog of [de, en]) {
      expect(Object.keys(catalog.notices.kind).sort()).toEqual(
        ["BACKUP_FAILED", "CONTRACT_CANCELLATION_REMINDER", "DISK_SPACE_LOW", "JOB_STALE", "TASK_ASSIGNED", "TASK_COMPLETED"],
      );
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

  it("names a stale background job by its status page name and its last success", () => {
    const lastSuccessAt = "2026-09-22T03:30:00Z";
    const stale = notice("JOB_STALE", { job: "reconcile-review-inbox", state: "STALE", lastSuccessAt });
    expect(noticeText(german, stale, "de")).toBe(
      `Der Hintergrundjob „Prüfkorb abgleichen“ läuft nicht mehr wie geplant. Letzter erfolgreicher Lauf: ${formatDateTime(lastSuccessAt, "de")}.`,
    );
    expect(noticeText(english, stale, "en")).toBe(
      `The background job “Review inbox reconciliation” has stopped running as planned. Last successful run: ${formatDateTime(lastSuccessAt, "en")}.`,
    );
  });

  it("says when a stale job never succeeded or is no longer scheduled", () => {
    const never = notice("JOB_STALE", { job: "check-archive-text", state: "STALE" });
    expect(noticeText(german, never, "de")).toBe(
      "Der Hintergrundjob „Texterkennung prüfen“ läuft nicht wie geplant und war noch nie erfolgreich.",
    );
    const unscheduled = notice("JOB_STALE", { job: "reconcile-documents", state: "NOT_SCHEDULED" });
    expect(noticeText(english, unscheduled, "en")).toBe(
      "The background job “Document processing reconciliation” is no longer scheduled and does not run.",
    );
  });

  it("keeps an unknown job's name and falls back to the generic text for an unknown state", () => {
    const unknownJob = notice("JOB_STALE", { job: "future-job", state: "NOT_SCHEDULED" });
    expect(noticeText(english, unknownJob, "en")).toContain("“future-job”");
    const incomplete: Record<string, string>[] = [{ job: "reconcile-documents", state: "SOMETHING_NEW" }, {}];
    for (const params of incomplete) {
      expect(noticeText(german, notice("JOB_STALE", params), "de")).toBe(
        "Ein Hintergrundjob läuft nicht wie geplant. Details stehen auf der Statusseite.",
      );
    }
  });

  it("uses the same variables for the job notices in both catalogs", () => {
    const expected: Record<string, string[]> = {
      STALE: ["job", "lastSuccess"],
      NEVER_SUCCEEDED: ["job"],
      NOT_SCHEDULED: ["job"],
    };
    for (const catalog of [de, en]) {
      expect(Object.keys(catalog.notices.jobStale).sort()).toEqual(Object.keys(expected).sort());
      for (const [key, variables] of Object.entries(expected)) {
        const text = (catalog.notices.jobStale as Record<string, string>)[key];
        const used = [...text.matchAll(/\{\{\s*(\w+)\s*\}\}/g)].map((match) => match[1]).sort();
        expect(used, key).toEqual(variables);
      }
    }
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

  it("names the backup failure with its localized reason and the last success", () => {
    const lastSuccessAt = "2026-09-23T01:31:30Z";
    const failed = notice("BACKUP_FAILED", { state: "FAILED", reason: "destination.notMounted", lastSuccessAt });
    expect(noticeText(german, failed, "de")).toBe(
      `Das externe Backup ist fehlgeschlagen: Die Backup-Platte ist nicht eingebunden. Letztes erfolgreiches Backup: ${formatDateTime(lastSuccessAt, "de")}.`,
    );
    expect(noticeText(english, failed, "en")).toBe(
      `The external backup failed: The backup disk is not mounted. Last successful backup: ${formatDateTime(lastSuccessAt, "en")}.`,
    );
    const never = notice("BACKUP_FAILED", { state: "FAILED", reason: "something.new" });
    expect(noticeText(german, never, "de")).toBe("Das externe Backup ist fehlgeschlagen: Unbekannter Fehler.");
    const stale = notice("BACKUP_FAILED", { state: "STALE", lastSuccessAt });
    expect(noticeText(english, stale, "en")).toBe(
      `There has been no successful external backup since ${formatDateTime(lastSuccessAt, "en")}.`,
    );
    const unreadable = notice("BACKUP_FAILED", { state: "UNKNOWN", reason: "state.unreadable" });
    expect(noticeText(german, unreadable, "de")).toBe("Der Zustand des externen Backups lässt sich nicht lesen.");
    expect(noticeText(german, notice("BACKUP_FAILED", { state: "LATER" }), "de")).toBe(
      "Das externe Backup funktioniert nicht. Details stehen auf der Statusseite.",
    );
  });

  it("names the low disk with its free and total space", () => {
    const low = notice("DISK_SPACE_LOW", { disk: "data", freeBytes: String(4 * 1024 ** 3), totalBytes: String(93 * 1024 ** 3) });
    expect(noticeText(german, low, "de")).toBe("Auf „Datenspeicher des Servers“ wird der Platz knapp: nur noch 4 GB von 93 GB frei.");
    expect(noticeText(english, low, "en")).toBe("“Server data storage” is running low on space: only 4 GB of 93 GB free.");
    expect(noticeText(english, notice("DISK_SPACE_LOW", { disk: "backup", freeBytes: "x", totalBytes: "1" }), "en")).toBe(
      "Storage is running low. See the status page for details.",
    );
  });

  it("uses the same variables for the backup and disk notices in both catalogs", () => {
    const expected: Record<string, string[]> = {
      FAILED: ["lastSuccess", "reason"],
      FAILED_NEVER: ["reason"],
      STALE: ["lastSuccess"],
      INTERRUPTED: [],
      UNKNOWN: [],
    };
    for (const catalog of [de, en]) {
      expect(Object.keys(catalog.notices.backupFailed).sort()).toEqual(Object.keys(expected).sort());
      for (const [key, variables] of Object.entries(expected)) {
        const text = (catalog.notices.backupFailed as Record<string, string>)[key];
        const used = [...text.matchAll(/\{\{\s*(\w+)\s*\}\}/g)].map((match) => match[1]).sort();
        expect(used, key).toEqual(variables);
      }
      const disk = [...catalog.notices.diskSpaceLow.matchAll(/\{\{\s*(\w+)\s*\}\}/g)].map((match) => match[1]).sort();
      expect(disk).toEqual(["disk", "free", "total"]);
    }
  });
});
