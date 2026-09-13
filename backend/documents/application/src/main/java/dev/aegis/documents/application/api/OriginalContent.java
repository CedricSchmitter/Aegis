package dev.aegis.documents.application.api;

/** Original bytes of a document together with the metadata needed to serve them unchanged. */
public record OriginalContent(String filename, String contentType, byte[] bytes, String source) {
    public static final String SOURCE_ARCHIVE = "ARCHIVE";
    public static final String SOURCE_STAGING = "STAGING";
}
