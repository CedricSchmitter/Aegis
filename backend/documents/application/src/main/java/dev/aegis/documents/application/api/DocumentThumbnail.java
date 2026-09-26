package dev.aegis.documents.application.api;

/**
 * A small image of a document's first page that the archive renders, for previews in lists and on phones. It is
 * derived data of the archive, like the archive PDF: it never replaces the original and is not exported.
 * {@code contentType} is one of the image types the adapter verified from the bytes.
 */
public record DocumentThumbnail(String contentType, byte[] bytes) {
}
