package dev.aegis.documents.application;

import dev.aegis.documents.domain.pages.PageEditPlan;

/**
 * Bounds of the page editor (ADR-0024). {@code maxOutputBytes} is the upload limit, which every new document has to
 * meet like an upload; {@code maxAttempts} bounds automatic retries while the archive cannot be read.
 */
public record PageEditSettings(PageEditPlan.Limits limits, long maxOutputBytes, int maxAttempts,
        int previewLongSide) {

    public PageEditSettings {
        if (maxOutputBytes < 1 || maxAttempts < 1 || previewLongSide < 64 || previewLongSide > 2048) {
            throw new IllegalArgumentException("invalid page edit settings");
        }
    }
}
