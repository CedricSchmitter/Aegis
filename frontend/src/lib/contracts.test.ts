import { describe, expect, it } from "vitest";
import type { TFunction } from "i18next";
import type { ContractFactDto } from "../api/types";
import {
  FACT_TYPES,
  VALUE_KIND_OF,
  formatFactValue,
  groupFacts,
  isHistory,
  parseMoney,
  parsePeriod,
  sortFacts,
  toCanonicalValue,
  validityLabel,
} from "./contracts";
import { draftFromValue } from "../components/ContractFactFields";

/** Renders `key{arg=value}` so a test can assert which key was chosen and what was interpolated. */
const t = ((key: string, options?: Record<string, unknown>) => {
  const suffix = options
    ? Object.entries(options)
        .map(([name, value]) => `${name}=${String(value)}`)
        .join(",")
    : "";
  // Mirrors i18next plural resolution closely enough to tell the two forms apart.
  const plural = options && typeof options.count === "number" && options.count === 1 ? "_one" : "";
  return suffix ? `${key}${plural}{${suffix}}` : `${key}${plural}`;
}) as unknown as TFunction;

function fact(overrides: Partial<ContractFactDto> = {}): ContractFactDto {
  return {
    id: "f1",
    type: "END_DATE",
    valueKind: "DATE",
    value: "2028-01-01",
    reviewState: "CONFIRMED",
    origin: "MANUAL",
    note: "",
    observedAt: "2026-09-20T10:00:00Z",
    effective: true,
    ...overrides,
  } as ContractFactDto;
}

describe("contract value presentation", () => {
  it("parses the ISO periods the backend stores, including normalized combinations", () => {
    expect(parsePeriod("P3M")).toEqual({ years: 0, months: 3, days: 0 });
    expect(parsePeriod("P1Y6M")).toEqual({ years: 1, months: 6, days: 0 });
    expect(parsePeriod("P30D")).toEqual({ years: 0, months: 0, days: 30 });
    expect(parsePeriod("P2W")).toEqual({ years: 0, months: 0, days: 14 });
  });

  it("refuses text that is not a period instead of guessing at one", () => {
    expect(parsePeriod("3 months")).toBeNull();
    expect(parsePeriod("P")).toBeNull();
    expect(parsePeriod("")).toBeNull();
  });

  it("reads an amount only together with its currency", () => {
    expect(parseMoney("29.90 EUR")).toEqual({ amount: 29.9, currency: "EUR" });
    expect(parseMoney("29.90")).toBeNull();
    expect(parseMoney("29,90 EUR")).toBeNull();
  });

  it("shows each value kind in the reader's language", () => {
    expect(formatFactValue("DATE", "2028-01-01", t, "en")).toBe("1 Jan 2028");
    expect(formatFactValue("DATE", "2028-01-01", t, "de")).toBe("01.01.2028");
    expect(formatFactValue("PERIOD", "P3M", t, "de")).toBe("contracts.period.months{count=3}");
    expect(formatFactValue("PERIOD", "P1M", t, "de")).toBe("contracts.period.months_one{count=1}");
    expect(formatFactValue("FLAG", "true", t, "de")).toBe("app.yes");
    expect(formatFactValue("TEXT", "Kundennummer 4711", t, "de")).toBe("Kundennummer 4711");
  });

  it("leaves a value it cannot interpret unchanged rather than inventing one", () => {
    expect(formatFactValue("PERIOD", "every month", t, "en")).toBe("every month");
    expect(formatFactValue("MONEY", "29.90", t, "en")).toBe("29.90");
  });

  it("round trips every value kind through the entry form", () => {
    for (const type of FACT_TYPES) {
      const kind = VALUE_KIND_OF[type];
      const original = { TEXT: "Acme AG", DATE: "2028-01-01", MONEY: "29.9 EUR", PERIOD: "P3M", FLAG: "false" }[kind];
      expect(toCanonicalValue(kind, draftFromValue(kind, original)), type).toBe(original);
    }
  });

  it("builds the canonical form the backend parses", () => {
    const draft = { text: "", amount: "49.90", currency: "eur", periodAmount: "2", periodUnit: "Y" as const, flag: "true" };
    expect(toCanonicalValue("MONEY", draft)).toBe("49.90 EUR");
    expect(toCanonicalValue("PERIOD", draft)).toBe("P2Y");
  });

  it("reports an incomplete value as empty so the form can require it", () => {
    const draft = { text: " ", amount: "", currency: "EUR", periodAmount: "", periodUnit: "M" as const, flag: "true" };
    expect(toCanonicalValue("TEXT", draft)).toBe("");
    expect(toCanonicalValue("MONEY", draft)).toBe("");
    expect(toCanonicalValue("PERIOD", draft)).toBe("");
  });
});

describe("contract fact validity and history", () => {
  it("states an open validity as its own sentence instead of an empty field", () => {
    expect(validityLabel(fact(), t, "en")).toBe("contracts.validity.always");
    expect(validityLabel(fact({ validFrom: "2027-01-01" }), t, "en")).toBe(
      "contracts.validity.from{from=1 Jan 2027}",
    );
    expect(validityLabel(fact({ validUntil: "2027-01-01" }), t, "en")).toBe(
      "contracts.validity.until{until=1 Jan 2027}",
    );
    expect(validityLabel(fact({ validFrom: "2026-01-01", validUntil: "2027-01-01" }), t, "en")).toBe(
      "contracts.validity.between{from=1 Jan 2026,until=1 Jan 2027}",
    );
  });

  it("counts superseded and rejected observations as history, and pending ones as still open", () => {
    expect(isHistory(fact({ reviewState: "SUPERSEDED" }))).toBe(true);
    expect(isHistory(fact({ reviewState: "REJECTED" }))).toBe(true);
    expect(isHistory(fact({ reviewState: "EXTRACTED" }))).toBe(false);
    expect(isHistory(fact({ reviewState: "CONFIRMED" }))).toBe(false);
  });

  it("puts what holds now first and keeps the superseded observation readable below it", () => {
    const correction = fact({ id: "new", value: "2029-01-01", effective: true, corrects: "old" });
    const corrected = fact({ id: "old", reviewState: "SUPERSEDED", effective: false, supersededBy: "new" });
    const proposal = fact({ id: "proposed", reviewState: "EXTRACTED", origin: "AUTOMATIC", effective: false });

    expect(sortFacts([corrected, proposal, correction]).map((entry) => entry.id)).toEqual([
      "new",
      "proposed",
      "old",
    ]);
  });

  it("orders an amendment above the period it amends", () => {
    const amendment = fact({ id: "amendment", validFrom: "2027-01-01", effective: false });
    const original = fact({ id: "original", validFrom: "2025-01-01", effective: false });

    expect(sortFacts([original, amendment]).map((entry) => entry.id)).toEqual(["amendment", "original"]);
  });

  it("groups facts in display order and keeps an unknown type in its own group", () => {
    const groups = groupFacts([
      fact({ id: "a", type: "NOTICE_PERIOD" }),
      fact({ id: "b", type: "COUNTERPARTY" }),
      fact({ id: "c", type: "SOMETHING_NEW" }),
    ]);

    expect(groups.map((group) => group.type)).toEqual(["COUNTERPARTY", "NOTICE_PERIOD", "SOMETHING_NEW"]);
  });
});
