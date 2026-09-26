import { describe, expect, it } from "vitest";
import { formatShortDate, formatWeekday } from "./format";

describe("formatShortDate", () => {
  it("leaves out the year within the current year", () => {
    expect(formatShortDate("2026-10-14", "de", "2026-09-26")).toBe("14.10.");
    expect(formatShortDate("2026-10-14", "en", "2026-09-26")).toBe("14 Oct");
  });

  it("keeps the year of a date in another year", () => {
    expect(formatShortDate("2027-01-05", "de", "2026-09-26")).toBe("05.01.2027");
  });
});

describe("formatWeekday", () => {
  it("names the weekday in the member's language", () => {
    expect(formatWeekday("2026-09-26", "de")).toBe("Samstag");
    expect(formatWeekday("2026-09-26", "en")).toBe("Saturday");
  });
});
