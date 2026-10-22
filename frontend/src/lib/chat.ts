import type { ChatCitationDto, ChatProgressDto, ChatTurnDto } from "../api/types";

/** How often an open question is checked while its run works. */
export const CHAT_POLL_MS = 1500;
/** Longest question the server accepts. */
export const MAX_QUESTION = 2000;
/** Longest search the server accepts. */
export const MAX_SEARCH = 200;
/** How long typing pauses before earlier questions are searched. */
export const CHAT_SEARCH_DELAY_MS = 250;

export type AnswerSegment = { kind: "text"; text: string } | { kind: "citation"; number: number };

/**
 * Splits an answer into text and citation markers such as [2] or [1, 3]. A marker that names no citation stays text,
 * so a bracketed number in the answer itself is never turned into a broken link.
 */
export function answerSegments(answer: string, citations: readonly ChatCitationDto[]): AnswerSegment[] {
  // Answers are plain text; bold markers a model adds anyway would show as asterisks.
  const text = answer.replace(/\*\*(.+?)\*\*/g, "$1");
  const known = new Set(citations.map((citation) => citation.number));
  const segments: AnswerSegment[] = [];
  let rest = 0;
  for (const match of text.matchAll(/\[(\d+(?:\s*,\s*\d+)*)\]/g)) {
    const numbers = match[1].split(",").map((part) => Number(part.trim()));
    if (!numbers.every((number) => known.has(number))) continue;
    const at = match.index ?? 0;
    if (at > rest) segments.push({ kind: "text", text: text.slice(rest, at) });
    for (const number of numbers) segments.push({ kind: "citation", number });
    rest = at + match[0].length;
  }
  if (rest < text.length) segments.push({ kind: "text", text: text.slice(rest) });
  return segments;
}

/** Whether the question is still being answered, so the page keeps checking it and the composer waits. */
export function turnOpen(turn: Pick<ChatTurnDto, "state"> | undefined): boolean {
  return turn?.state === "WAITING" || turn?.state === "WORKING";
}

/** A failed or cancelled latest question can be asked again with the same words. */
export function turnRetryable(turn: Pick<ChatTurnDto, "state" | "answer">): boolean {
  return (turn.state === "FAILED" || turn.state === "CANCELLED") && !turn.answer;
}

/**
 * What a waiting question tells the member. Only the daily budget names a time: its renewal is exact, while a retry
 * after a provider failure is a guess that can lie hours before the provider's own reset.
 */
export function waitingMessage(turn: Pick<ChatTurnDto, "failureCode" | "nextAttemptAt">): { key: string; at?: string } {
  if (turn.failureCode === "budget.daily" && turn.nextAttemptAt) return { key: "chat.waitingUntil", at: turn.nextAttemptAt };
  if (turn.failureCode === "provider.rateLimited") return { key: "chat.waitingRateLimited" };
  if (turn.failureCode) return { key: "chat.waitingRetry" };
  return { key: "chat.waiting" };
}

const TOOL_KEYS: Record<string, string> = {
  household_catalog: "chat.progress.catalog",
  search_documents: "chat.progress.searchDocuments",
  search_document_text: "chat.progress.searchDocuments",
  read_document: "chat.progress.readDocument",
  find_in_document: "chat.progress.findInDocument",
  list_contracts: "chat.progress.listContracts",
  read_contract: "chat.progress.readContract",
  list_tasks: "chat.progress.listTasks",
};

/**
 * The visible steps of an open question as translation keys, oldest first. Model calls collapse into the step that
 * follows; repeated tool calls of one kind in a row are counted.
 */
export function progressSteps(progress: readonly ChatProgressDto[]): { key: string; count: number }[] {
  const steps: { key: string; count: number }[] = [];
  for (const step of progress) {
    let key: string | null = null;
    if (step.kind === "TOOL") key = TOOL_KEYS[step.tool ?? ""] ?? "chat.progress.otherTool";
    else if (step.kind === "ATTEMPT") key = "chat.progress.started";
    if (!key) continue;
    const last = steps.at(-1);
    if (last && last.key === key) last.count += 1;
    else steps.push({ key, count: 1 });
  }
  return steps;
}

/** Where a cited source opens in the app. */
export function citationTarget(citation: Pick<ChatCitationDto, "kind" | "sourceId">): string {
  switch (citation.kind) {
    case "CONTRACT":
      return `/contracts/${citation.sourceId}`;
    case "TASK":
      return `/tasks/${citation.sourceId}`;
    default:
      return `/documents/${citation.sourceId}`;
  }
}

/** A new client-chosen id, so a repeated request after a lost response does not ask twice. */
export function newId(): string {
  return crypto.randomUUID();
}
