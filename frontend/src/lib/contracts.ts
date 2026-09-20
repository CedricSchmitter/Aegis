import type { TFunction } from "i18next";
import type { ContractFactDto } from "../api/types";
import { formatDate, formatMoney } from "./format";

/**
 * Presentation of contract facts. Values travel in the canonical, locale-independent form the backend stores
 * (`2028-01-01`, `P3M`, `29.90 EUR`, `true`); this module turns them into German or English text and back, so no
 * component has to know how a value is written on the wire.
 */

/** Fact types in the order the review screen shows them; identity first, then term, rules and money. */
export const FACT_TYPES = [
  "COUNTERPARTY",
  "CONTRACT_NUMBER",
  "START_DATE",
  "END_DATE",
  "MINIMUM_TERM",
  "RENEWAL_TERM",
  "NOTICE_PERIOD",
  "AUTOMATIC_RENEWAL",
  "CANCELLATION_DEADLINE",
  "AMOUNT",
  "BILLING_PERIOD",
] as const;
export type FactType = (typeof FACT_TYPES)[number];

export const VALUE_KINDS = ["TEXT", "DATE", "MONEY", "PERIOD", "FLAG"] as const;
export type ValueKind = (typeof VALUE_KINDS)[number];

export const REVIEW_STATES = ["EXTRACTED", "CONFIRMED", "REJECTED", "SUPERSEDED"] as const;
export type ReviewState = (typeof REVIEW_STATES)[number];

export const FACT_ORIGINS = ["MANUAL", "AUTOMATIC"] as const;
export const CONTRACT_STATUSES = ["ACTIVE", "CANCELLED", "ENDED"] as const;
export const CONFLICT_KINDS = ["CONFIRMED_AGAINST_CONFIRMED", "EXTRACTED_AGAINST_CONFIRMED"] as const;
export const DEADLINE_BASES = ["STATED_DEADLINE", "NOTICE_PERIOD_BEFORE_END"] as const;

/** Mirrors `FactType.valueKind()`; the form has to offer the input the backend will accept. */
export const VALUE_KIND_OF: Record<FactType, ValueKind> = {
  COUNTERPARTY: "TEXT",
  CONTRACT_NUMBER: "TEXT",
  START_DATE: "DATE",
  END_DATE: "DATE",
  MINIMUM_TERM: "PERIOD",
  RENEWAL_TERM: "PERIOD",
  NOTICE_PERIOD: "PERIOD",
  AUTOMATIC_RENEWAL: "FLAG",
  CANCELLATION_DEADLINE: "DATE",
  AMOUNT: "MONEY",
  BILLING_PERIOD: "PERIOD",
};

export const PERIOD_UNITS = ["Y", "M", "D"] as const;
export type PeriodUnit = (typeof PERIOD_UNITS)[number];

/** Years, months and days of an ISO-8601 period; `null` when the text is not one Aegis would have stored. */
export function parsePeriod(text: string): { years: number; months: number; days: number } | null {
  const match = /^P(?:(\d+)Y)?(?:(\d+)M)?(?:(\d+)W)?(?:(\d+)D)?$/.exec(text.trim());
  if (!match || match.slice(1).every((part) => part === undefined)) return null;
  const [years, months, weeks, days] = match.slice(1).map((part) => (part ? Number(part) : 0));
  return { years, months, days: days + weeks * 7 };
}

/** "1 year 6 months" / "1 Jahr 6 Monate". Unparseable text is shown unchanged rather than guessed at. */
export function formatPeriod(text: string, t: TFunction): string {
  const period = parsePeriod(text);
  if (!period) return text;
  const parts: string[] = [];
  if (period.years) parts.push(t("contracts.period.years", { count: period.years }));
  if (period.months) parts.push(t("contracts.period.months", { count: period.months }));
  if (period.days) parts.push(t("contracts.period.days", { count: period.days }));
  return parts.length > 0 ? parts.join(" ") : text;
}

/** Amount and currency as the backend writes them, for example `29.90 EUR`. */
export function parseMoney(text: string): { amount: number; currency: string } | null {
  const match = /^(-?\d+(?:\.\d+)?)\s+([A-Z]{3})$/.exec(text.trim());
  if (!match) return null;
  return { amount: Number(match[1]), currency: match[2] };
}

/** One fact value in the reader's language; every kind keeps its meaning, nothing is silently reinterpreted. */
export function formatFactValue(kind: string, value: string, t: TFunction, language: string): string {
  switch (kind) {
    case "DATE":
      return formatDate(value, language) || value;
    case "PERIOD":
      return formatPeriod(value, t);
    case "MONEY": {
      const money = parseMoney(value);
      return money ? formatMoney(money.amount, money.currency, language) : value;
    }
    case "FLAG":
      return value === "true" ? t("app.yes") : t("app.no");
    default:
      return value;
  }
}

/** The canonical text an entered value is sent as. Empty parts stay empty so the form can require them. */
export function toCanonicalValue(
  kind: ValueKind,
  input: { text: string; amount: string; currency: string; periodAmount: string; periodUnit: PeriodUnit; flag: string },
): string {
  switch (kind) {
    case "DATE":
    case "TEXT":
      return input.text.trim();
    case "MONEY":
      return input.amount.trim() && input.currency.trim()
        ? `${input.amount.trim()} ${input.currency.trim().toUpperCase()}`
        : "";
    case "PERIOD":
      return input.periodAmount.trim() ? `P${input.periodAmount.trim()}${input.periodUnit}` : "";
    case "FLAG":
      return input.flag;
  }
}

/**
 * How long a fact describes the contract. An open period is not an unknown one: a fact without dates describes
 * the contract throughout, which the caller renders as its own sentence rather than as an empty field.
 */
export function validityLabel(fact: ContractFactDto, t: TFunction, language: string): string {
  const from = fact.validFrom ? formatDate(fact.validFrom, language) : null;
  const until = fact.validUntil ? formatDate(fact.validUntil, language) : null;
  if (from && until) return t("contracts.validity.between", { from, until });
  if (from) return t("contracts.validity.from", { from });
  if (until) return t("contracts.validity.until", { until });
  return t("contracts.validity.always");
}

/** Review order: what holds now first, then other decisions, then the history a member can still read. */
const STATE_RANK: Record<string, number> = { CONFIRMED: 0, EXTRACTED: 1, REJECTED: 2, SUPERSEDED: 3 };

/** Facts of one type, newest validity first, so an amendment reads above the period it amends. */
export function sortFacts(facts: ContractFactDto[]): ContractFactDto[] {
  return [...facts].sort((a, b) => {
    if (a.effective !== b.effective) return a.effective ? -1 : 1;
    const rank = (STATE_RANK[a.reviewState] ?? 9) - (STATE_RANK[b.reviewState] ?? 9);
    if (rank !== 0) return rank;
    const validity = (b.validFrom ?? "").localeCompare(a.validFrom ?? "");
    if (validity !== 0) return validity;
    return b.observedAt.localeCompare(a.observedAt);
  });
}

/** Facts grouped by type in {@link FACT_TYPES} order; unknown types from a newer backend keep their own group. */
export function groupFacts(facts: ContractFactDto[]): { type: string; facts: ContractFactDto[] }[] {
  const known = FACT_TYPES.filter((type) => facts.some((fact) => fact.type === type));
  const unknown = [...new Set(facts.map((fact) => fact.type))].filter(
    (type) => !FACT_TYPES.includes(type as FactType),
  );
  return [...known, ...unknown].map((type) => ({
    type,
    facts: sortFacts(facts.filter((fact) => fact.type === type)),
  }));
}

/** A fact only counts as history when the household can no longer act on it. */
export function isHistory(fact: ContractFactDto): boolean {
  return fact.reviewState === "SUPERSEDED" || fact.reviewState === "REJECTED";
}

/** A one-line reference to another observation, used where a fact points at the fact it corrects or contradicts. */
export function factSummary(fact: ContractFactDto, t: TFunction, language: string): string {
  return t("contracts.fact.summary", {
    type: t(`contracts.factType.${fact.type}`, { defaultValue: fact.type }),
    value: formatFactValue(fact.valueKind, fact.value, t, language),
  });
}
