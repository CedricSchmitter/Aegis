import type { components } from "./schema";

// Single place that maps generated schema names to the names used in components.
type Schemas = components["schemas"];

export type MemberDto = Schemas["MemberDto"];
export type DocumentDto = Schemas["DocumentDto"];
export type SubjectDto = Schemas["SubjectDto"];
export type ProcessingDto = Schemas["ProcessingDto"];
export type DocumentListResponse = Schemas["DocumentListResponse"];
export type UploadResponse = Schemas["UploadResponse"];
export type TaskDto = Schemas["TaskDto"];
export type TodayResponse = Schemas["TodayResponse"];
export type NoticeSummary = Schemas["NoticeSummary"];
export type SystemStatus = Schemas["SystemStatus"];
export type IntegrationStatus = Schemas["IntegrationStatus"];
export type ExportSummary = Schemas["ExportSummary"];
export type VersionResponse = Schemas["VersionResponse"];
export type ProductLocale = MemberDto["locale"];
