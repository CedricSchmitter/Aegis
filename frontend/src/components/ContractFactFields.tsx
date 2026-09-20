import { useTranslation } from "react-i18next";
import {
  PERIOD_UNITS,
  parseMoney,
  parsePeriod,
  toCanonicalValue,
  type PeriodUnit,
  type ValueKind,
} from "../lib/contracts";

/**
 * The input for one fact value. Every fact type fixes the shape of its value, so the form offers the shape the
 * backend will accept instead of a free text field the member can only get wrong.
 */
export interface ValueDraft {
  text: string;
  amount: string;
  currency: string;
  periodAmount: string;
  periodUnit: PeriodUnit;
  flag: string;
}

export const EMPTY_VALUE_DRAFT: ValueDraft = {
  text: "",
  amount: "",
  currency: "EUR",
  periodAmount: "",
  periodUnit: "M",
  flag: "true",
};

/** Fills the draft from a stored canonical value, so correcting starts at the value the member is looking at. */
export function draftFromValue(kind: ValueKind, value: string): ValueDraft {
  const draft = { ...EMPTY_VALUE_DRAFT };
  switch (kind) {
    case "TEXT":
    case "DATE":
      draft.text = value;
      break;
    case "MONEY": {
      const money = parseMoney(value);
      if (money) {
        draft.amount = String(money.amount);
        draft.currency = money.currency;
      }
      break;
    }
    case "PERIOD": {
      const period = parsePeriod(value);
      // Only a single-unit period round trips into the two fields; anything else stays empty rather than lossy.
      if (period?.years && !period.months && !period.days) {
        draft.periodAmount = String(period.years);
        draft.periodUnit = "Y";
      } else if (period?.months && !period.years && !period.days) {
        draft.periodAmount = String(period.months);
        draft.periodUnit = "M";
      } else if (period?.days && !period.years && !period.months) {
        draft.periodAmount = String(period.days);
        draft.periodUnit = "D";
      }
      break;
    }
    case "FLAG":
      draft.flag = value === "false" ? "false" : "true";
      break;
  }
  return draft;
}

export function canonicalValue(kind: ValueKind, draft: ValueDraft): string {
  return toCanonicalValue(kind, draft);
}

interface Props {
  kind: ValueKind;
  draft: ValueDraft;
  onChange(draft: ValueDraft): void;
  /** Keeps field ids unique when the page shows several fact forms at once. */
  idPrefix: string;
}

export function FactValueFields({ kind, draft, onChange, idPrefix }: Props) {
  const { t } = useTranslation();
  const patch = (change: Partial<ValueDraft>) => onChange({ ...draft, ...change });

  if (kind === "DATE") {
    return (
      <div>
        <label className="label" htmlFor={`${idPrefix}-date`}>
          {t("contracts.fact.valueDate")}
        </label>
        <input
          id={`${idPrefix}-date`}
          type="date"
          className="field"
          value={draft.text}
          onChange={(e) => patch({ text: e.target.value })}
        />
      </div>
    );
  }

  if (kind === "MONEY") {
    return (
      <div className="flex gap-2">
        <div className="flex-1">
          <label className="label" htmlFor={`${idPrefix}-amount`}>
            {t("contracts.fact.valueAmount")}
          </label>
          <input
            id={`${idPrefix}-amount`}
            type="number"
            inputMode="decimal"
            step="0.01"
            className="field"
            value={draft.amount}
            onChange={(e) => patch({ amount: e.target.value })}
          />
        </div>
        <div className="w-28">
          <label className="label" htmlFor={`${idPrefix}-currency`}>
            {t("contracts.fact.valueCurrency")}
          </label>
          <input
            id={`${idPrefix}-currency`}
            type="text"
            maxLength={3}
            className="field uppercase"
            value={draft.currency}
            onChange={(e) => patch({ currency: e.target.value })}
          />
        </div>
      </div>
    );
  }

  if (kind === "PERIOD") {
    return (
      <div className="flex gap-2">
        <div className="flex-1">
          <label className="label" htmlFor={`${idPrefix}-period`}>
            {t("contracts.fact.valuePeriod")}
          </label>
          <input
            id={`${idPrefix}-period`}
            type="number"
            inputMode="numeric"
            min={1}
            className="field"
            value={draft.periodAmount}
            onChange={(e) => patch({ periodAmount: e.target.value })}
          />
        </div>
        <div className="w-40">
          <label className="label" htmlFor={`${idPrefix}-unit`}>
            {t("contracts.fact.valuePeriodUnit")}
          </label>
          <select
            id={`${idPrefix}-unit`}
            className="field"
            value={draft.periodUnit}
            onChange={(e) => patch({ periodUnit: e.target.value as PeriodUnit })}
          >
            {PERIOD_UNITS.map((unit) => (
              <option key={unit} value={unit}>
                {t(`contracts.periodUnit.${unit}`)}
              </option>
            ))}
          </select>
        </div>
      </div>
    );
  }

  if (kind === "FLAG") {
    return (
      <div>
        <label className="label" htmlFor={`${idPrefix}-flag`}>
          {t("contracts.fact.valueFlag")}
        </label>
        <select
          id={`${idPrefix}-flag`}
          className="field"
          value={draft.flag}
          onChange={(e) => patch({ flag: e.target.value })}
        >
          <option value="true">{t("app.yes")}</option>
          <option value="false">{t("app.no")}</option>
        </select>
      </div>
    );
  }

  return (
    <div>
      <label className="label" htmlFor={`${idPrefix}-text`}>
        {t("contracts.fact.valueText")}
      </label>
      <input
        id={`${idPrefix}-text`}
        type="text"
        className="field"
        value={draft.text}
        onChange={(e) => patch({ text: e.target.value })}
      />
    </div>
  );
}

/** Validity of the statement, not of the contract: an amendment starts later instead of replacing the old value. */
export function ValidityFields({
  from,
  until,
  onChange,
  idPrefix,
}: {
  from: string;
  until: string;
  onChange(change: { from: string; until: string }): void;
  idPrefix: string;
}) {
  const { t } = useTranslation();
  return (
    <div className="flex gap-2">
      <div className="flex-1">
        <label className="label" htmlFor={`${idPrefix}-valid-from`}>
          {t("contracts.fact.validFrom")}
        </label>
        <input
          id={`${idPrefix}-valid-from`}
          type="date"
          className="field"
          value={from}
          onChange={(e) => onChange({ from: e.target.value, until })}
        />
      </div>
      <div className="flex-1">
        <label className="label" htmlFor={`${idPrefix}-valid-until`}>
          {t("contracts.fact.validUntil")}
        </label>
        <input
          id={`${idPrefix}-valid-until`}
          type="date"
          className="field"
          value={until}
          onChange={(e) => onChange({ from, until: e.target.value })}
        />
      </div>
    </div>
  );
}
