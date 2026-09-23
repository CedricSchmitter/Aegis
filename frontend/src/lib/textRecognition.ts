import type { DocumentDto } from "../api/types";

type Recognition = DocumentDto["textRecognition"];

/** How long after archival a page keeps refreshing while the text check has not arrived yet. */
export const TEXT_CHECK_WAIT_MS = 3 * 60_000;

/** The archive recognized no or almost no text, so full-text search can hardly find the document (ADR-0012). */
export function lacksText(recognition: Recognition): recognition is Recognition & { state: "NONE" | "LITTLE" } {
  return recognition.state === "NONE" || recognition.state === "LITTLE";
}

/**
 * A freshly archived document whose text check is still pending: the archival job records it moments after the
 * archived state, so a page refreshes a little longer. Bounded, so an unreachable archive does not poll forever.
 */
export function awaitingTextCheck(document: DocumentDto, now = Date.now()): boolean {
  if (document.processing.state !== "ARCHIVED" || document.textRecognition.state !== "PENDING") return false;
  const archivedAt = document.processing.archivedAt ? Date.parse(document.processing.archivedAt) : Number.NaN;
  return Number.isFinite(archivedAt) && Math.abs(now - archivedAt) < TEXT_CHECK_WAIT_MS;
}
