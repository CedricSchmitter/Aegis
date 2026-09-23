package dev.aegis.documents.application.api;

/**
 * The searchable PDF the archive generated from a document's original, for example from a photographed letter.
 * {@code filename} is the document's friendly filename with the {@code .pdf} extension. It is a rendition, not the
 * original: downloads of the original and exports keep the original bytes.
 */
public record ArchivePdf(String filename, byte[] bytes) {
}
