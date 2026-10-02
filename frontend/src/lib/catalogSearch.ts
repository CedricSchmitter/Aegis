import type { Facet, FacetGroup } from "../api/types";

export type MatchMode = "ANY" | "ALL";
export type SortOrder = "NEWEST" | "OLDEST" | "TITLE";

/** The complete search state; it lives in the page URL so a filtered view can be shared or bookmarked. */
export interface CatalogFilters {
  query: string;
  /** Folder id, "unsorted" for documents without a folder, or null for the whole collection. */
  category: string | null;
  subfolders: boolean;
  type: string[];
  organization: string[];
  topic: string[];
  tag: string[];
  subject: string[];
  topicMatch: MatchMode;
  tagMatch: MatchMode;
  missing: FacetGroup[];
  sort: SortOrder;
  page: number;
  size: number;
}

export const UNSORTED = "unsorted";
export const DEFAULT_SIZE = 25;

export const EMPTY_FILTERS: CatalogFilters = {
  query: "",
  category: null,
  subfolders: true,
  type: [],
  organization: [],
  topic: [],
  tag: [],
  subject: [],
  topicMatch: "ANY",
  tagMatch: "ANY",
  missing: [],
  sort: "NEWEST",
  page: 0,
  size: DEFAULT_SIZE,
};

const FACET_PARAM: Record<Facet, keyof CatalogFilters> = {
  DOCUMENT_TYPE: "type",
  ORGANIZATION: "organization",
  TOPIC: "topic",
  TAG: "tag",
};

/** The request parameter name of a facet group; subjects use their own list. */
export function facetParam(facet: Facet): "type" | "organization" | "topic" | "tag" {
  return FACET_PARAM[facet] as "type" | "organization" | "topic" | "tag";
}

export function selectedValues(filters: CatalogFilters, group: FacetGroup): string[] {
  return group === "SUBJECT" ? filters.subject : (filters[facetParam(group)] as string[]);
}

export function parseFilters(params: URLSearchParams): CatalogFilters {
  const list = (name: string) => params.getAll(name).filter((value) => value.length > 0);
  const number = (name: string, fallback: number) => {
    const raw = Number(params.get(name));
    return Number.isFinite(raw) && raw >= 0 ? Math.trunc(raw) : fallback;
  };
  const match = (name: string): MatchMode => (params.get(name) === "ALL" ? "ALL" : "ANY");
  const sort = params.get("sort");
  return {
    query: params.get("query") ?? "",
    category: params.get("category"),
    subfolders: params.get("subfolders") !== "false",
    type: list("type"),
    organization: list("organization"),
    topic: list("topic"),
    tag: list("tag"),
    subject: list("subject"),
    topicMatch: match("topicMatch"),
    tagMatch: match("tagMatch"),
    missing: list("missing") as FacetGroup[],
    sort: sort === "OLDEST" || sort === "TITLE" ? sort : "NEWEST",
    page: number("page", 0),
    size: number("size", DEFAULT_SIZE) || DEFAULT_SIZE,
  };
}

/** Serializes only what differs from the defaults, so shared links stay readable. */
export function toSearchParams(filters: CatalogFilters): URLSearchParams {
  const params = new URLSearchParams();
  if (filters.query.trim()) params.set("query", filters.query.trim());
  if (filters.category) params.set("category", filters.category);
  if (!filters.subfolders) params.set("subfolders", "false");
  for (const name of ["type", "organization", "topic", "tag", "subject", "missing"] as const) {
    for (const value of filters[name]) params.append(name, value);
  }
  if (filters.topicMatch === "ALL") params.set("topicMatch", "ALL");
  if (filters.tagMatch === "ALL") params.set("tagMatch", "ALL");
  if (filters.sort !== "NEWEST") params.set("sort", filters.sort);
  if (filters.page > 0) params.set("page", String(filters.page));
  if (filters.size !== DEFAULT_SIZE) params.set("size", String(filters.size));
  return params;
}

/** Query parameters of the document search endpoint; empty groups are left out. */
export interface CatalogQuery {
  query?: string;
  category?: string;
  subfolders: boolean;
  type?: string[];
  organization?: string[];
  topic?: string[];
  tag?: string[];
  subject?: string[];
  topicMatch: MatchMode;
  tagMatch: MatchMode;
  missing?: string[];
  sort: SortOrder;
  page: number;
  size: number;
}

export function toApiQuery(filters: CatalogFilters): CatalogQuery {
  const query: CatalogQuery = {
    subfolders: filters.subfolders,
    topicMatch: filters.topicMatch,
    tagMatch: filters.tagMatch,
    sort: filters.sort,
    page: filters.page,
    size: filters.size,
  };
  if (filters.query.trim()) query.query = filters.query.trim();
  if (filters.category) query.category = filters.category;
  if (filters.type.length) query.type = filters.type;
  if (filters.organization.length) query.organization = filters.organization;
  if (filters.topic.length) query.topic = filters.topic;
  if (filters.tag.length) query.tag = filters.tag;
  if (filters.subject.length) query.subject = filters.subject;
  if (filters.missing.length) query.missing = filters.missing;
  return query;
}

export function toggleValue(values: string[], value: string): string[] {
  return values.includes(value) ? values.filter((entry) => entry !== value) : [...values, value];
}

/** Number of active constraints; used for the filter summary and the clear-all control. */
export function activeFilterCount(filters: CatalogFilters): number {
  return (
    (filters.query.trim() ? 1 : 0) +
    (filters.category ? 1 : 0) +
    filters.type.length +
    filters.organization.length +
    filters.topic.length +
    filters.tag.length +
    filters.subject.length +
    filters.missing.length
  );
}

export function withoutFilters(filters: CatalogFilters): CatalogFilters {
  return { ...EMPTY_FILTERS, sort: filters.sort, size: filters.size };
}

/**
 * Whether the filter shows a facet group. A group without any value to choose would only offer "not set", which
 * every listed document matches, so it is left out; an active "not set" filter keeps it to be cleared.
 */
export function showsFacetGroup(valueKeys: string[], missingSelected: boolean): boolean {
  return valueKeys.length > 0 || missingSelected;
}

/** Number of chosen facet values and "not set" filters, the ones the filter panel sets; folder and search not included. */
export function facetFilterCount(filters: CatalogFilters): number {
  return (
    filters.type.length +
    filters.organization.length +
    filters.topic.length +
    filters.tag.length +
    filters.subject.length +
    filters.missing.length
  );
}

/** Clears the filter panel and keeps the folder, the search text, the order and the page size. */
export function withoutFacets(filters: CatalogFilters): CatalogFilters {
  return {
    ...EMPTY_FILTERS,
    query: filters.query,
    category: filters.category,
    subfolders: filters.subfolders,
    sort: filters.sort,
    size: filters.size,
  };
}
