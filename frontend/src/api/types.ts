import type { components } from "./schema";

// Single place that maps generated schema names to the names used in components.
type Schemas = components["schemas"];

export type MemberDto = Schemas["MemberDto"];
export type AppearanceDto = Schemas["AppearanceDto"];
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
export type HealthCheck = Schemas["HealthCheckDto"];
export type ExportSummary = Schemas["ExportSummaryDto"];
export type VersionResponse = Schemas["VersionResponse"];
export type AiOverview = Schemas["AiOverviewDto"];
export type BulkAnalysisDto = Schemas["BulkAnalysisDto"];
export type BulkPreviewDto = Schemas["BulkPreviewDto"];
export type BulkScopeDto = Schemas["BulkScopeDto"];
export type AiRun = Schemas["RunDto"];
export type ContractDto = Schemas["ContractDto"];
export type ContractSummaryDto = Schemas["SummaryDto"];
export type ContractFactDto = Schemas["FactDto"];
export type ContractSourceDto = Schemas["ContractSourceDto"];
export type ContractConflictDto = Schemas["ConflictDto"];
export type ContractDeadlineDto = Schemas["DeadlineDto"];
export type ContractDeadlineItemDto = Schemas["DeadlineItemDto"];
export type ProductLocale = MemberDto["locale"];
export type ChatOverviewDto = Schemas["ChatOverviewDto"];
export type ConversationSummaryDto = Schemas["ConversationSummaryDto"];
export type ConversationDto = Schemas["ConversationDto"];
export type ChatTurnDto = Schemas["ChatTurnDto"];
export type ChatProgressDto = Schemas["ChatProgressDto"];
export type ChatAnswerDto = Schemas["ChatAnswerDto"];
export type ChatCitationDto = Schemas["ChatCitationDto"];

/** Facet groups in display order; subjects and the folder are separate groups. */
export const FACETS = ["DOCUMENT_TYPE", "ORGANIZATION", "TOPIC", "TAG"] as const;
export type Facet = (typeof FACETS)[number];

/** Groups that the search API filters and counts, including subjects. */
export const FACET_GROUPS = [...FACETS, "SUBJECT"] as const;
export type FacetGroup = (typeof FACET_GROUPS)[number];
