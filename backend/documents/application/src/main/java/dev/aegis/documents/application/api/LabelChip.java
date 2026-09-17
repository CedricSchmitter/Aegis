package dev.aegis.documents.application.api;

import java.util.UUID;

/**
 * A label shown on a document. {@code source} is {@code OWN} for an assignment stored on the document and
 * {@code FOLDER} for a value inherited from {@code folderId}. Own chips carry origin ({@code MANUAL} or
 * {@code AUTOMATIC}), review state and, when reported, the classifier confidence.
 */
public record LabelChip(UUID labelId, String facet, String builtinKey, String name, String source, String origin,
        String reviewState, Double confidence, String sourceRef, UUID folderId) {
    public static final String SOURCE_OWN = "OWN";
    public static final String SOURCE_FOLDER = "FOLDER";
}
