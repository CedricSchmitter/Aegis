import type { DocumentDto } from "../api/types";
import { isImageContentType } from "./uploadFormat";

/**
 * What the document page says about the archive's searchable PDF (ADR-0011, ADR-0016): for a photo original it is
 * promised until archival and then offered; for a PDF only when the archive keeps an archive version, as for scanned
 * pages without a text layer. A born-digital PDF has its own text and no archive version, so nothing is offered.
 */
export type SearchablePdf = "none" | "photoPending" | "photo" | "scan";

export function searchablePdf(document: Pick<DocumentDto, "contentType" | "processing" | "archivePdfAvailable">):
  SearchablePdf {
  if (isImageContentType(document.contentType)) {
    return document.processing.state === "ARCHIVED" ? "photo" : "photoPending";
  }
  return document.archivePdfAvailable ? "scan" : "none";
}
