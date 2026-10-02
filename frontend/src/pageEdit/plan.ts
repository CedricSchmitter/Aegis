import type { components } from "../api/schema";
import { subjectFromKey, subjectKey } from "../lib/labels";

/*
 * The page editor's plan (ADR-0024): an ordered list of new documents ("outputs"), each an ordered list of page
 * references. Splitting puts a marker between two pages, merging adds pages of other documents, reordering moves a
 * reference, rotating turns it, removing drops it. Sources are never changed; every output becomes a new document.
 */

export type PageEditRequest = components["schemas"]["PageEditRequestDto"];
export type PagesDto = components["schemas"]["PagesDto"];

/** Limits of one request; the server checks them again (`pageEdit.limit.*`). */
export const LIMITS = { outputs: 10, pagesPerOutput: 200, sources: 20, newPages: 30 } as const;

export type Rotation = 0 | 90 | 180 | 270;

/** A page of a stored document (zero-based `pageIndex`) or a newly photographed page of the editor's scan. */
export type PlanPage =
  | { key: string; source: "DOCUMENT"; documentId: string; pageIndex: number; rotation: Rotation }
  | { key: string; source: "SCAN"; scanPageId: string; rotation: Rotation };

/** Folder and subjects (as subject keys, see `subjectKey`) a new document is created with. */
export interface OutputDetails {
  categoryId: string | null;
  subjects: string[];
}

/**
 * A new document. `title` and `details` are null while they follow the prefill from the source of the first page;
 * once the member changes them they are kept as chosen.
 */
export interface PlanOutput {
  key: string;
  title: string | null;
  details: OutputDetails | null;
  pages: PlanPage[];
}

/** What the editor knows of a source document: its title, folder, subjects and pages as last loaded. */
export interface SourceInfo {
  documentId: string;
  title: string;
  categoryId: string | null;
  subjects: string[];
  sourceKind: PagesDto["sourceKind"];
  pages: { width: number; height: number }[];
  warnings: string[];
}

/**
 * The plan in progress of one member. `operationId` is the id of the request; it stays while the plan is sent
 * unchanged, so a repeated request after a lost answer finds the operation instead of creating documents twice.
 * `sentFingerprint` is what was last sent under that id; `pending` is set once the server accepted the request.
 */
export interface PagePlan {
  memberId: string;
  operationId: string;
  outputs: PlanOutput[];
  sources: SourceInfo[];
  sentFingerprint: string | null;
  pending: boolean;
}

export function emptyPlan(memberId: string, newId: () => string = () => crypto.randomUUID()): PagePlan {
  return { memberId, operationId: newId(), outputs: [], sources: [], sentFingerprint: null, pending: false };
}

const newKey = () => crypto.randomUUID();

export function pageCount(plan: PagePlan): number {
  return plan.outputs.reduce((sum, output) => sum + output.pages.length, 0);
}

/** Ids of the editor's new pages in the order they first appear in the plan: their order in the attached PDF. */
export function scanOrder(plan: PagePlan): string[] {
  const ids: string[] = [];
  plan.outputs.forEach((output) =>
    output.pages.forEach((page) => {
      if (page.source === "SCAN" && !ids.includes(page.scanPageId)) ids.push(page.scanPageId);
    }),
  );
  return ids;
}

/** Documents whose pages the plan uses, in order of first use. */
export function usedDocuments(plan: PagePlan): string[] {
  const ids: string[] = [];
  plan.outputs.forEach((output) =>
    output.pages.forEach((page) => {
      if (page.source === "DOCUMENT" && !ids.includes(page.documentId)) ids.push(page.documentId);
    }),
  );
  return ids;
}

/*
 * Moves work on a flat sequence of tokens: the pages of all outputs in order, with a marker before every output but
 * the first. A page moved over a marker changes document, and a document left without pages disappears together
 * with its marker.
 */
type Token = { kind: "page"; page: PlanPage } | { kind: "marker"; output: Omit<PlanOutput, "pages"> };

function toTokens(outputs: readonly PlanOutput[]): { head: Omit<PlanOutput, "pages"> | null; tokens: Token[] } {
  const tokens: Token[] = [];
  let head: Omit<PlanOutput, "pages"> | null = null;
  outputs.forEach((output, index) => {
    const { pages, ...meta } = output;
    if (index === 0) head = meta;
    else tokens.push({ kind: "marker", output: meta });
    pages.forEach((page) => tokens.push({ kind: "page", page }));
  });
  return { head, tokens };
}

function fromTokens(head: Omit<PlanOutput, "pages"> | null, tokens: readonly Token[]): PlanOutput[] {
  const outputs: PlanOutput[] = [];
  let current: PlanOutput | null = head ? { ...head, pages: [] } : null;
  for (const token of tokens) {
    if (token.kind === "marker") {
      if (current && current.pages.length > 0) outputs.push(current);
      // An empty document before this marker gives way; the marker's document takes its place.
      current = { ...token.output, pages: [] };
    } else {
      current ??= { key: newKey(), title: null, details: null, pages: [] };
      current.pages.push(token.page);
    }
  }
  if (current && current.pages.length > 0) outputs.push(current);
  return outputs;
}

function withOutputs(plan: PagePlan, outputs: PlanOutput[]): PagePlan {
  return { ...plan, outputs };
}

/** Where a page is: the document's position and the page's position in it, or null. */
export function locate(plan: PagePlan, pageKey: string): { output: number; position: number } | null {
  for (let output = 0; output < plan.outputs.length; output++) {
    const position = plan.outputs[output].pages.findIndex((page) => page.key === pageKey);
    if (position >= 0) return { output, position };
  }
  return null;
}

/** Moves a page one place earlier (-1) or later (+1); at a document's edge it moves into the neighbouring document. */
export function movePage(plan: PagePlan, pageKey: string, delta: -1 | 1): PagePlan {
  const { head, tokens } = toTokens(plan.outputs);
  const from = tokens.findIndex((token) => token.kind === "page" && token.page.key === pageKey);
  const to = from + delta;
  if (from < 0 || to < 0 || to >= tokens.length) return plan;
  const moved = [...tokens];
  [moved[from], moved[to]] = [moved[to], moved[from]];
  return withOutputs(plan, fromTokens(head, moved));
}

/** Moves a page to `position` of the document `outputKey` (as counted before the move), for drag and drop. */
export function movePageTo(plan: PagePlan, pageKey: string, outputKey: string, position: number): PagePlan {
  const source = locate(plan, pageKey);
  const target = plan.outputs.findIndex((output) => output.key === outputKey);
  if (!source || target < 0) return plan;
  const page = plan.outputs[source.output].pages[source.position];
  let insertAt = Math.max(0, Math.min(position, plan.outputs[target].pages.length));
  if (source.output === target && source.position < insertAt) insertAt -= 1;
  if (source.output === target && source.position === insertAt) return plan;
  const outputs = plan.outputs.map((output, index) => ({
    ...output,
    pages: index === source.output ? output.pages.filter((entry) => entry.key !== pageKey) : output.pages,
  }));
  outputs[target] = { ...outputs[target], pages: [...outputs[target].pages] };
  outputs[target].pages.splice(insertAt, 0, page);
  return withOutputs(plan, outputs.filter((output) => output.pages.length > 0));
}

/** Puts a marker before the page at `position`: the pages from there on become a new document. */
export function splitBefore(plan: PagePlan, outputKey: string, position: number): PagePlan {
  const index = plan.outputs.findIndex((output) => output.key === outputKey);
  if (index < 0 || plan.outputs.length >= LIMITS.outputs) return plan;
  const output = plan.outputs[index];
  if (position <= 0 || position >= output.pages.length) return plan;
  const outputs = [...plan.outputs];
  outputs.splice(
    index,
    1,
    { ...output, pages: output.pages.slice(0, position) },
    { key: newKey(), title: null, details: null, pages: output.pages.slice(position) },
  );
  return withOutputs(plan, outputs);
}

/** Removes the marker before a document: its pages join the previous document, which keeps its title. */
export function joinWithPrevious(plan: PagePlan, outputKey: string): PagePlan {
  const index = plan.outputs.findIndex((output) => output.key === outputKey);
  if (index <= 0) return plan;
  const outputs = [...plan.outputs];
  const previous = outputs[index - 1];
  outputs.splice(index - 1, 2, { ...previous, pages: [...previous.pages, ...outputs[index].pages] });
  return withOutputs(plan, outputs);
}

export function rotatePage(plan: PagePlan, pageKey: string): PagePlan {
  return withOutputs(
    plan,
    plan.outputs.map((output) => ({
      ...output,
      pages: output.pages.map((page) =>
        page.key === pageKey ? { ...page, rotation: ((page.rotation + 90) % 360) as Rotation } : page,
      ),
    })),
  );
}

/** Leaves a page out; a document without pages disappears. */
export function removePage(plan: PagePlan, pageKey: string): PagePlan {
  return withOutputs(
    plan,
    plan.outputs
      .map((output) => ({ ...output, pages: output.pages.filter((page) => page.key !== pageKey) }))
      .filter((output) => output.pages.length > 0),
  );
}

/** Drops references to new pages that no longer exist (for example a photo that could not be opened). */
export function keepScanPages(plan: PagePlan, existing: readonly string[]): PagePlan {
  const missing = scanOrder(plan).filter((id) => !existing.includes(id));
  if (missing.length === 0) return plan;
  return withOutputs(
    plan,
    plan.outputs
      .map((output) => ({
        ...output,
        pages: output.pages.filter((page) => page.source !== "SCAN" || !missing.includes(page.scanPageId)),
      }))
      .filter((output) => output.pages.length > 0),
  );
}

/** Leaves out every reference to one new page, for example when it is discarded in the corner editor. */
export function removeScanPage(plan: PagePlan, scanPageId: string): PagePlan {
  return keepScanPages(plan, scanOrder(plan).filter((id) => id !== scanPageId));
}

function appendPages(plan: PagePlan, pages: PlanPage[]): PagePlan {
  if (pages.length === 0) return plan;
  if (plan.outputs.length === 0) {
    return withOutputs(plan, [{ key: newKey(), title: null, details: null, pages }]);
  }
  const outputs = [...plan.outputs];
  const last = outputs[outputs.length - 1];
  outputs[outputs.length - 1] = { ...last, pages: [...last.pages, ...pages] };
  return withOutputs(plan, outputs);
}

/** Adds all pages of a document at the end of the last new document (merging) and remembers the source. */
export function addDocument(plan: PagePlan, source: SourceInfo): PagePlan {
  const sources = [...plan.sources.filter((entry) => entry.documentId !== source.documentId), source];
  const pages: PlanPage[] = source.pages.map((_, pageIndex) => ({
    key: newKey(),
    source: "DOCUMENT",
    documentId: source.documentId,
    pageIndex,
    rotation: 0,
  }));
  return appendPages({ ...plan, sources }, pages);
}

/** Adds newly photographed pages at the end of the last new document. */
export function addScanPages(plan: PagePlan, scanPageIds: readonly string[]): PagePlan {
  return appendPages(
    plan,
    scanPageIds.map((scanPageId) => ({ key: newKey(), source: "SCAN", scanPageId, rotation: 0 })),
  );
}

export function setTitle(plan: PagePlan, outputKey: string, title: string): PagePlan {
  return withOutputs(plan, plan.outputs.map((output) => (output.key === outputKey ? { ...output, title } : output)));
}

export function setDetails(plan: PagePlan, outputKey: string, details: OutputDetails): PagePlan {
  return withOutputs(plan, plan.outputs.map((output) => (output.key === outputKey ? { ...output, details } : output)));
}

export function sourceOf(plan: PagePlan, documentId: string): SourceInfo | undefined {
  return plan.sources.find((source) => source.documentId === documentId);
}

/** The source document of an output's first page; undefined when it starts with a new page. */
export function firstSource(plan: PagePlan, output: PlanOutput): SourceInfo | undefined {
  const first = output.pages[0];
  return first?.source === "DOCUMENT" ? sourceOf(plan, first.documentId) : undefined;
}

/** How prefilled titles are worded in the member's language. */
export interface TitleWords {
  /** Title of a document that starts with new pages. */
  newPages: string;
  /** Title of one of several documents taken from the same source, for example "Stromrechnung, Teil 2". */
  part: (title: string, number: number) => string;
}

function baseTitle(plan: PagePlan, output: PlanOutput, words: TitleWords): string {
  return firstSource(plan, output)?.title ?? words.newPages;
}

/** The title prefilled for the output at `index`: its first page's source, numbered when several share it. */
export function prefilledTitle(plan: PagePlan, index: number, words: TitleWords): string {
  const base = baseTitle(plan, plan.outputs[index], words);
  const sharing = plan.outputs.filter((output) => baseTitle(plan, output, words) === base);
  if (sharing.length < 2) return base;
  return words.part(base, sharing.indexOf(plan.outputs[index]) + 1);
}

export function titleOf(plan: PagePlan, index: number, words: TitleWords): string {
  return plan.outputs[index].title ?? prefilledTitle(plan, index, words);
}

/** Folder and subjects: as chosen, or those of the first page's source (Unsorted and none for a new page). */
export function detailsOf(plan: PagePlan, output: PlanOutput): OutputDetails {
  if (output.details) return output.details;
  const source = firstSource(plan, output);
  return { categoryId: source?.categoryId ?? null, subjects: source?.subjects ?? [] };
}

export function sourceSubjects(subjects: components["schemas"]["SubjectDto"][]): string[] {
  return subjects.map(subjectKey);
}

/** Why the plan cannot be sent yet, as error codes of the API (localized under `errors.`). */
export function planProblems(plan: PagePlan, words: TitleWords): string[] {
  const problems: string[] = [];
  if (pageCount(plan) === 0) problems.push("pageEdit.plan.empty");
  if (plan.outputs.length > LIMITS.outputs) problems.push("pageEdit.limit.outputs");
  if (plan.outputs.some((output) => output.pages.length > LIMITS.pagesPerOutput)) problems.push("pageEdit.limit.pages");
  if (usedDocuments(plan).length > LIMITS.sources) problems.push("pageEdit.limit.sources");
  if (scanOrder(plan).length > LIMITS.newPages) problems.push("pageEdit.limit.newPages");
  if (plan.outputs.some((_, index) => !titleOf(plan, index, words).trim())) problems.push("document.title.missing");
  return problems;
}

/** The request as the API takes it; new pages are numbered by their position in the attached PDF. */
export function toRequest(plan: PagePlan, words: TitleWords): PageEditRequest {
  const scans = scanOrder(plan);
  return {
    id: plan.operationId,
    outputs: plan.outputs.map((output, index) => {
      const details = detailsOf(plan, output);
      return {
        title: titleOf(plan, index, words).trim(),
        titleChosen: output.title !== null,
        categoryId: details.categoryId,
        subjects: details.subjects.map(subjectFromKey),
        pages: output.pages.map((page) =>
          page.source === "DOCUMENT"
            ? { source: "DOCUMENT", documentId: page.documentId, pageIndex: page.pageIndex, rotation: page.rotation }
            : { source: "SCAN", documentId: null, pageIndex: scans.indexOf(page.scanPageId), rotation: page.rotation },
        ),
      };
    }),
  };
}

/**
 * Prepares sending: the same content keeps the operation id, so a retry after a lost answer is recognized; changed
 * content gets a new id, because the server refuses a different plan under an id it already knows.
 * `scanSignature` stands for the new pages' content (for example their corners), which the plan alone does not show.
 */
export function prepareSend(
  plan: PagePlan,
  words: TitleWords,
  scanSignature: string,
  newId: () => string = () => crypto.randomUUID(),
): { plan: PagePlan; request: PageEditRequest } {
  const fingerprint = (request: PageEditRequest) => JSON.stringify({ outputs: request.outputs, scanSignature });
  let current = plan;
  if (plan.sentFingerprint !== null && plan.sentFingerprint !== fingerprint(toRequest(plan, words))) {
    current = { ...plan, operationId: newId() };
  }
  const request = toRequest(current, words);
  return { plan: { ...current, sentFingerprint: fingerprint(request) }, request };
}

/** After a failed operation or a refused id the plan is sent again as a new operation. */
export function freshOperation(plan: PagePlan, newId: () => string = () => crypto.randomUUID()): PagePlan {
  return { ...plan, operationId: newId(), sentFingerprint: null, pending: false };
}
