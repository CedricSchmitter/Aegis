import { describe, expect, it } from "vitest";
import {
  DEFAULT_SIZE,
  EMPTY_FILTERS,
  activeFilterCount,
  facetFilterCount,
  facetParam,
  parseFilters,
  selectedValues,
  showsFacetGroup,
  toApiQuery,
  toSearchParams,
  toggleValue,
  withoutFacets,
  withoutFilters,
  type CatalogFilters,
} from "./catalogSearch";

const filters: CatalogFilters = {
  ...EMPTY_FILTERS,
  query: " telekom ",
  category: "11111111-1111-4111-8111-111111111111",
  subfolders: false,
  type: ["type-a"],
  organization: ["org-a", "org-b"],
  topic: ["topic-a"],
  tag: [],
  subject: ["HOUSE", "MEMBER:m-1"],
  topicMatch: "ALL",
  missing: ["TAG"],
  sort: "TITLE",
  page: 2,
};

describe("catalog search state", () => {
  it("survives a round trip through the URL", () => {
    const parsed = parseFilters(toSearchParams(filters));

    expect(parsed).toEqual({ ...filters, query: "telekom" });
  });

  it("keeps shared links free of default values", () => {
    const params = toSearchParams(EMPTY_FILTERS);

    expect(params.toString()).toBe("");
    expect(parseFilters(new URLSearchParams())).toEqual(EMPTY_FILTERS);
  });

  it("repeats multi-valued groups and skips empty ones in the API query", () => {
    const query = toApiQuery(filters);

    expect(query.organization).toEqual(["org-a", "org-b"]);
    expect(query.tag).toBeUndefined();
    expect(query.query).toBe("telekom");
    expect(query.topicMatch).toBe("ALL");
    expect(query.subfolders).toBe(false);
    expect(query.size).toBe(DEFAULT_SIZE);
  });

  it("reads unsorted and malformed parameters defensively", () => {
    const parsed = parseFilters(new URLSearchParams("category=unsorted&page=-3&size=abc&sort=WHATEVER&type="));

    expect(parsed.category).toBe("unsorted");
    expect(parsed.page).toBe(0);
    expect(parsed.size).toBe(DEFAULT_SIZE);
    expect(parsed.sort).toBe("NEWEST");
    expect(parsed.type).toEqual([]);
  });

  it("toggles values and counts active constraints", () => {
    expect(toggleValue(["a"], "b")).toEqual(["a", "b"]);
    expect(toggleValue(["a", "b"], "a")).toEqual(["b"]);
    expect(activeFilterCount(filters)).toBe(1 + 1 + 1 + 2 + 1 + 2 + 1);
    expect(activeFilterCount(EMPTY_FILTERS)).toBe(0);
    expect(withoutFilters(filters)).toEqual({ ...EMPTY_FILTERS, sort: "TITLE", size: DEFAULT_SIZE });
  });

  it("maps facet groups to their request parameters", () => {
    expect(facetParam("DOCUMENT_TYPE")).toBe("type");
    expect(selectedValues(filters, "ORGANIZATION")).toEqual(["org-a", "org-b"]);
    expect(selectedValues(filters, "SUBJECT")).toEqual(["HOUSE", "MEMBER:m-1"]);
  });

  it("hides a facet group whose only choice would be \"not set\" unless that filter is active", () => {
    expect(showsFacetGroup([], false)).toBe(false);
    expect(showsFacetGroup([], true)).toBe(true);
    expect(showsFacetGroup(["tag-a"], false)).toBe(true);
  });
});

describe("filter panel", () => {
  it("counts only the values chosen in the panel and clears them without leaving the folder or the search", () => {
    expect(facetFilterCount(filters)).toBe(7);
    expect(facetFilterCount(EMPTY_FILTERS)).toBe(0);
    expect(withoutFacets(filters)).toEqual({
      ...EMPTY_FILTERS,
      query: filters.query,
      category: filters.category,
      subfolders: false,
      sort: "TITLE",
    });
  });
});
