package dev.aegis.documents.application.api;

/**
 * Names of a document. {@code filename} is the friendly download and export name; {@code source} is
 * {@code GENERATED} (from evidenced date, organization, type and topic), {@code FALLBACK} (title and stable id only)
 * or {@code MANUAL} ({@code override}, the member's stem). The original upload filename stays in
 * {@link DocumentSummary#originalFilename()}; the archive's internal storage name is not exposed.
 */
public record FilenameView(String filename, String source, String override) {
    public static final String GENERATED = "GENERATED";
    public static final String FALLBACK = "FALLBACK";
    public static final String MANUAL = "MANUAL";
}
