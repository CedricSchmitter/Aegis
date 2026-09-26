import type { TFunction } from "i18next";

/** Locale-aware presentation helpers. Stored values stay ISO; only the display changes with the language. */
export function formatDate(value: string | null | undefined, language: string): string {
  if (!value) return "";
  const date = value.length === 10 ? new Date(value + "T00:00:00") : new Date(value);
  return new Intl.DateTimeFormat(language === "en" ? "en-GB" : "de-DE", { dateStyle: "medium" }).format(date);
}

/**
 * A date in a card: day and month only within the current year ("14.10." / "14 Oct"), the full date otherwise, so a
 * date from another year is never mistaken for this one.
 */
export function formatShortDate(value: string | null | undefined, language: string, today = todayIso()): string {
  if (!value) return "";
  if (value.substring(0, 4) !== today.substring(0, 4)) return formatDate(value, language);
  const date = new Date(value.substring(0, 10) + "T00:00:00");
  return language === "en"
    ? new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short" }).format(date)
    : new Intl.DateTimeFormat("de-DE", { day: "2-digit", month: "2-digit" }).format(date);
}

/** "Samstag" / "Saturday" for an ISO local date. */
export function formatWeekday(value: string, language: string): string {
  return new Intl.DateTimeFormat(language === "en" ? "en-GB" : "de-DE", { weekday: "long" }).format(new Date(value + "T00:00:00"));
}

export function formatDateTime(value: string | null | undefined, language: string): string {
  if (!value) return "";
  return new Intl.DateTimeFormat(language === "en" ? "en-GB" : "de-DE", {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(new Date(value));
}

export function formatBytes(bytes: number, t: TFunction, language: string): string {
  const number = new Intl.NumberFormat(language === "en" ? "en-GB" : "de-DE", { maximumFractionDigits: 1 });
  if (bytes < 1024) return t("format.bytes", { value: number.format(bytes) });
  if (bytes < 1024 * 1024) return t("format.kilobytes", { value: number.format(bytes / 1024) });
  if (bytes < 1024 * 1024 * 1024) return t("format.megabytes", { value: number.format(bytes / (1024 * 1024)) });
  return t("format.gigabytes", { value: number.format(bytes / (1024 * 1024 * 1024)) });
}

/** Amounts with their explicit currency; AI costs are small, so up to four decimals are shown. */
export function formatMoney(amount: number, currency: string, language: string): string {
  return new Intl.NumberFormat(language === "en" ? "en-GB" : "de-DE", {
    style: "currency",
    currency,
    minimumFractionDigits: 2,
    maximumFractionDigits: 4,
  }).format(amount);
}

export function todayIso(): string {
  const now = new Date();
  const local = new Date(now.getTime() - now.getTimezoneOffset() * 60000);
  return local.toISOString().substring(0, 10);
}
