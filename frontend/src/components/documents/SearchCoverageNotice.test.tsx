// @vitest-environment jsdom
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, expect, it } from "vitest";
import type { DocumentListResponse } from "../../api/types";
import i18n from "../../i18n";
import { SearchCoverageNotice } from "./SearchCoverageNotice";
import { FacetFilters } from "../FacetFilters";
import { EMPTY_FILTERS } from "../../lib/catalogSearch";

afterEach(cleanup);

it.each(["de", "en"])("explains limited, interrupted and unavailable retrieval in %s", async (locale) => {
  await i18n.changeLanguage(locale);
  for (const [contentSearch, searchLimitation, expected] of [
    ["LIMITED", "REQUEST_LIMIT", locale === "de" ? "Grenze" : "limit"],
    ["LIMITED", "ARCHIVE_FAILURE", locale === "de" ? "beendet" : "stopped"],
    ["UNAVAILABLE", "ARCHIVE_FAILURE", locale === "de" ? "Titeltreffer" : "title matches"],
  ] as const) {
    const result = { contentSearch, searchLimitation } as DocumentListResponse;
    const { unmount } = render(<SearchCoverageNotice result={result} />);
    expect(screen.getByRole("alert").textContent).toContain(expected);
    expect(screen.getByRole("alert").textContent).not.toContain("documents.");
    unmount();
  }
  render(<SearchCoverageNotice result={{ contentSearch: "READY" } as DocumentListResponse} />);
  expect(screen.queryByRole("alert")).toBeNull();
});

it.each(["de", "en"])("labels partial facet counts as lower bounds in %s", async (locale) => {
  await i18n.changeLanguage(locale);
  render(<FacetFilters filters={EMPTY_FILTERS} facets={{ values: { SUBJECT: { HOUSE: 2 } }, missing: { SUBJECT: 3 } }}
    partial labels={[]} members={[]} onChange={() => {}} />);
  expect(screen.getByTestId("facet-SUBJECT-HOUSE").textContent).toContain(locale === "de" ? "Mindestens 2" : "At least 2");
  expect(screen.getByTestId("facet-missing-SUBJECT").textContent).toContain(locale === "de" ? "Mindestens 3" : "At least 3");
});
