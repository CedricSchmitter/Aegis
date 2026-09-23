/**
 * File formats Aegis accepts as a document original: PDFs and photos. The server recognizes the format from the
 * content (ADR-0011); this mirrors its rules so the app can answer early and clearly. Names and reported types are
 * not reliable: cameras, galleries and sharing apps use generic names and `application/octet-stream`.
 */
export type UploadFormat = "pdf" | "jpeg" | "png" | "webp" | "heic";

/** Leading bytes needed to recognize every format; the server reads the same number. */
export const SIGNATURE_LENGTH = 16;

/** Matches aegis.archival.max-upload-bytes; the server checks again, this only gives an early, clear answer. */
export const MAX_UPLOAD_BYTES = 50 * 1024 * 1024;

export const CONTENT_TYPES: Record<UploadFormat, string> = {
  pdf: "application/pdf",
  jpeg: "image/jpeg",
  png: "image/png",
  webp: "image/webp",
  heic: "image/heic",
};

const EXTENSIONS: Record<UploadFormat, readonly string[]> = {
  pdf: [".pdf"],
  jpeg: [".jpg", ".jpeg", ".jpe", ".jfif"],
  png: [".png"],
  webp: [".webp"],
  heic: [".heic", ".heif"],
};

/** Gallery and photo picker: the image types Aegis accepts (HEIF is listed because phones report HEIC as such). */
export const PHOTO_ACCEPT = "image/jpeg,image/png,image/webp,image/heic,image/heif,.jpg,.jpeg,.png,.webp,.heic,.heif";

/** File chooser: PDFs and photos. */
export const DOCUMENT_ACCEPT = `application/pdf,.pdf,${PHOTO_ACCEPT}`;

const ascii = (head: Uint8Array, offset: number, length: number) =>
  head.length < offset + length ? "" : String.fromCharCode(...head.subarray(offset, offset + length));

const startsWith = (head: Uint8Array, offset: number, expected: readonly number[]) =>
  head.length >= offset + expected.length && expected.every((byte, index) => head[offset + index] === byte);

/** The format of the first bytes of a file, or null when it is none Aegis accepts (same rules as the server). */
export function detectFormatFromHead(head: Uint8Array): UploadFormat | null {
  if (ascii(head, 0, 5) === "%PDF-") return "pdf";
  if (startsWith(head, 0, [0xff, 0xd8, 0xff])) return "jpeg";
  if (startsWith(head, 0, [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])) {
    // The first chunk is always IHDR with 13 bytes; anything else is a damaged header.
    return startsWith(head, 8, [0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52]) ? "png" : null;
  }
  if (ascii(head, 0, 4) === "RIFF" && ascii(head, 8, 4) === "WEBP") {
    return ["VP8 ", "VP8L", "VP8X"].includes(ascii(head, 12, 4)) ? "webp" : null;
  }
  if (ascii(head, 4, 4) === "ftyp" && ["heic", "heix"].includes(ascii(head, 8, 4))) {
    const boxSize = ((head[0] << 24) >>> 0) + (head[1] << 16) + (head[2] << 8) + head[3];
    return boxSize >= 16 ? "heic" : null;
  }
  return null;
}

export async function detectFormat(content: Blob): Promise<UploadFormat | null> {
  return detectFormatFromHead(new Uint8Array(await content.slice(0, SIGNATURE_LENGTH).arrayBuffer()));
}

/**
 * Local check before anything is sent; returns the same error codes the server uses. `format` is what the content
 * is (see detectFormat), never what the name or the sending app claims.
 */
export function localRejection(file: { size: number }, format: UploadFormat | null): string | null {
  if (file.size === 0) return "document.empty";
  if (format === null) return "document.unsupportedType";
  if (file.size > MAX_UPLOAD_BYTES) return "document.tooLarge";
  return null;
}

export function isImage(format: UploadFormat | null): boolean {
  return format !== null && format !== "pdf";
}

/** Whether a stored document's media type is a photo, for example to offer its searchable PDF. */
export function isImageContentType(contentType: string): boolean {
  return Object.entries(CONTENT_TYPES).some(([format, type]) => format !== "pdf" && type === contentType);
}

/**
 * Name sent to the server. Some apps share without a name or without an extension; the server decides the type by
 * content anyway, but a readable name with the right extension becomes the initial title and original name.
 */
export function uploadName(name: string, fallbackBase: string, format: UploadFormat): string {
  const trimmed = name.trim();
  const base = trimmed === "" ? fallbackBase : trimmed;
  const lower = base.toLowerCase();
  const known = Object.values(EXTENSIONS).some((extensions) => extensions.some((ext) => lower.endsWith(ext)));
  return known ? base : `${base}${EXTENSIONS[format][0]}`;
}
