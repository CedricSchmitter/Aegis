package dev.aegis.documents.application.api;

/**
 * Original bytes of a document together with the metadata needed to serve them unchanged. {@code filename} is the
 * friendly download name; the bytes are never rewritten to carry it.
 */
public record OriginalContent(String filename, String contentType, byte[] bytes, String source) {
    public static final String SOURCE_ARCHIVE = "ARCHIVE";
    public static final String SOURCE_STAGING = "STAGING";
}
