import type { TFunction } from "i18next";

/** Locale-aware presentation helpers. Stored values stay ISO; only the display changes with the language. */
export function formatDate(value: string | null | undefined, language: string): string {
  if (!value) return "";
  const date = value.length === 10 ? new Date(value + "T00:00:00") : new Date(value);
  return new Intl.DateTimeFormat(language === "en" ? "en-GB" : "de-DE", { dateStyle: "medium" }).format(date);
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
  return t("format.megabytes", { value: number.format(bytes / (1024 * 1024)) });
}

export function todayIso(): string {
  const now = new Date();
  const local = new Date(now.getTime() - now.getTimezoneOffset() * 60000);
  return local.toISOString().substring(0, 10);
}
