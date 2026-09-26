import type { DocumentDto } from "../api/types";
import type { FileKind } from "../components/ui";
import { isImageContentType } from "./uploadFormat";

type Viewed = Pick<
  DocumentDto,
  "id" | "contentType" | "processing" | "archivePdfAvailable" | "originalAvailable" | "textRecognition" | "classification" | "analysis"
>;

/**
 * The type tag of a document: a photo original, a PDF of scanned pages (the archive keeps a searchable version of
 * it, ADR-0016) or an ordinary PDF.
 */
export function fileKind(document: Pick<DocumentDto, "contentType" | "archivePdfAvailable">): FileKind {
  if (isImageContentType(document.contentType)) return "photo";
  return document.archivePdfAvailable ? "scan" : "pdf";
}

/** Where a document stands in Gespeichert → Archiviert → Text erkannt → Eingeordnet. */
export interface DocumentProgress {
  completed: number;
  current: "running" | "failed" | "waiting";
}

/**
 * The four processing steps of a document. Saving is done once the document exists; archival can fail; text counts
 * as recognized when the archive found some (no text waits for a better capture); a document is filed once it lies
 * in a folder, and a running analysis counts as working on that step.
 */
export function documentProgress(document: Viewed): DocumentProgress {
  const processing = document.processing.state;
  if (processing === "FAILED") return { completed: 1, current: "failed" };
  if (processing !== "ARCHIVED") return { completed: 1, current: "running" };
  const text = document.textRecognition.state;
  if (text === "PENDING") return { completed: 2, current: "running" };
  if (text !== "RECOGNIZED" && text !== "LITTLE") return { completed: 2, current: "waiting" };
  if (document.classification.categoryId) return { completed: 4, current: "waiting" };
  const analysis = document.analysis.state;
  return { completed: 3, current: analysis === "PENDING" ? "running" : "waiting" };
}

/** Image types every current browser displays; HEIC is not among them. */
const DISPLAYABLE_IMAGES = new Set(["image/jpeg", "image/png", "image/webp"]);

export type ViewerSource =
  | { kind: "pdf"; url: string }
  | { kind: "image"; url: string }
  | { kind: "none"; reason: "unavailable" | "heic" };

/**
 * What the desktop viewer shows. A photo is shown as it was taken; a HEIC photo, which browsers cannot display, and
 * a PDF of scanned pages are shown as the archive's searchable PDF; any other PDF as the original.
 */
export function viewerSource(document: Viewed): ViewerSource {
  const original = `/api/documents/${document.id}/original?inline=true`;
  const searchable = `/api/documents/${document.id}/archive-pdf?inline=true`;
  if (isImageContentType(document.contentType)) {
    if (document.originalAvailable && DISPLAYABLE_IMAGES.has(document.contentType)) return { kind: "image", url: original };
    if (document.processing.state === "ARCHIVED") return { kind: "pdf", url: searchable };
    return { kind: "none", reason: document.originalAvailable ? "heic" : "unavailable" };
  }
  if (document.archivePdfAvailable) return { kind: "pdf", url: searchable };
  return document.originalAvailable ? { kind: "pdf", url: original } : { kind: "none", reason: "unavailable" };
}

/** The archive's image of the first page, available once the document is archived. */
export function thumbnailUrl(document: Pick<DocumentDto, "id" | "processing">): string | null {
  return document.processing.state === "ARCHIVED" ? `/api/documents/${document.id}/thumbnail` : null;
}
