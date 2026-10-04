import { describe, expect, it } from "vitest";
import { acceptsMoneyInput, formatMoneyInput, limitProblems, parseMoney } from "./costLimits";

describe("money input of the AI cost limits", () => {
  it("accepts only amounts with at most two decimals while typing", () => {
    for (const text of ["", "0", "12", "12,", "12,5", "0.25", "1000", ",5"]) {
      expect(acceptsMoneyInput(text), text).toBe(true);
    }
    for (const text of ["abc", "1a", "1,234", "1.2.3", "1,2,", "-1", "+1", "1 000", "1e3", "12345", "€5", " 5"]) {
      expect(acceptsMoneyInput(text), text).toBe(false);
    }
  });

  it("reads comma and point as the decimal separator", () => {
    expect(parseMoney("0,25")).toBe(0.25);
    expect(parseMoney("0.25")).toBe(0.25);
    expect(parseMoney("12,")).toBe(12);
    expect(parseMoney(",5")).toBe(0.5);
    expect(parseMoney("")).toBeNull();
    expect(parseMoney(",")).toBeNull();
    expect(parseMoney("abc")).toBeNull();
  });

  it("shows amounts with two decimals in the language's notation", () => {
    expect(formatMoneyInput(0.25, "de")).toBe("0,25");
    expect(formatMoneyInput(50, "de")).toBe("50,00");
    expect(formatMoneyInput(0.5, "en")).toBe("0.50");
  });

  it("names what keeps the limits from being saved", () => {
    expect(limitProblems("5,00", "0,50")).toEqual({});
    expect(limitProblems("0,50", "0,50")).toEqual({});
    expect(limitProblems("", "0,50")).toEqual({ daily: "required" });
    expect(limitProblems("0", "0,00")).toEqual({ daily: "notPositive", run: "notPositive" });
    expect(limitProblems("1000,01", "1")).toEqual({ daily: "tooHigh" });
    expect(limitProblems("0,50", "0,60")).toEqual({ run: "runAboveDaily" });
    expect(limitProblems("", "0,60")).toEqual({ daily: "required" });
  });
});
