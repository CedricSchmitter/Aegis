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
export type NoticeSummary = Schemas["NoticeDto"];
export type SystemStatus = Schemas["SystemStatusDto"];
export type IntegrationStatus = Schemas["IntegrationStatusDto"];
export type ExportSummary = Schemas["ExportSummaryDto"];
export type VersionResponse = Schemas["VersionResponse"];
export type ProductLocale = MemberDto["locale"];
