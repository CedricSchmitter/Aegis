import type { components } from "./schema";

// Single place that maps generated schema names to the names used in components.
type Schemas = components["schemas"];

export type MemberDto = Schemas["MemberDto"];
export type DocumentDto = Schemas["DocumentDto"];
export type SubjectDto = Schemas["SubjectDto"];
export type ProcessingDto = Schemas["ProcessingDto"];
export type AnalysisDto = Schemas["AnalysisDto"];
export type FilenameDto = Schemas["FilenameDto"];
export type DocumentListResponse = Schemas["DocumentListResponse"];
export type UploadResponse = Schemas["UploadResponse"];
export type CategoryDto = Schemas["CategoryDto"];
export type CategoryListResponse = Schemas["CategoryListResponse"];
export type CategoryRefDto = Schemas["CategoryRefDto"];
export type ClassificationDto = Schemas["ClassificationDto"];
export type LabelChipDto = Schemas["LabelChipDto"];
export type LabelDto = Schemas["LabelDto"];
export type FacetCounts = Schemas["FacetCounts"];
export type TaskDto = Schemas["TaskDto"];
export type TodayResponse = Schemas["TodayResponse"];
export type NoticeSummary = Schemas["NoticeDto"];
export type SystemStatus = Schemas["SystemStatusDto"];
export type IntegrationStatus = Schemas["IntegrationStatusDto"];
export type ExportSummary = Schemas["ExportSummaryDto"];
export type VersionResponse = Schemas["VersionResponse"];
export type AiOverview = Schemas["AiOverviewDto"];
export type AiRun = Schemas["RunDto"];
export type ProductLocale = MemberDto["locale"];

/** Facet groups in display order; subjects and the folder are separate groups. */
export const FACETS = ["DOCUMENT_TYPE", "ORGANIZATION", "TOPIC", "TAG"] as const;
export type Facet = (typeof FACETS)[number];

/** Groups that the search API filters and counts, including subjects. */
export const FACET_GROUPS = [...FACETS, "SUBJECT"] as const;
export type FacetGroup = (typeof FACET_GROUPS)[number];
