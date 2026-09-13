import { describe, expect, it } from "vitest";
import i18next from "i18next";
import de from "../i18n/de.json";
import en from "../i18n/en.json";
import { ApiError } from "../api/client";
import { problemMessage } from "./problem";

describe("problemMessage", () => {
  const i18n = i18next.createInstance();
  void i18n.init({ resources: { de: { translation: de }, en: { translation: en } }, lng: "de", fallbackLng: "de" });

  it("localizes known codes in both languages", () => {
    const error = new ApiError(409, { code: "version.conflict" });
    expect(problemMessage(i18n.getFixedT("de"), error)).toBe("Der Datensatz wurde inzwischen geändert.");
    expect(problemMessage(i18n.getFixedT("en"), error)).toBe("The record was changed in the meantime.");
  });

  it("falls back to a generic message for unknown codes and to a network message for connection failures", () => {
    expect(problemMessage(i18n.getFixedT("de"), new ApiError(500, { code: "something.new" }))).toBe(
      "Das hat nicht geklappt. Bitte versuche es erneut.",
    );
    expect(problemMessage(i18n.getFixedT("en"), new TypeError("Failed to fetch"))).toBe(
      "No connection to the server.",
    );
  });
});
