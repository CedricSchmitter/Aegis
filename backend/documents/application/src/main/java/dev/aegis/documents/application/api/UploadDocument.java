package dev.aegis.documents.application.api;

import dev.aegis.shared.Actor;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

/**
 * Durable capture of a PDF or a photo (JPEG, PNG, WebP, HEIC). Success is reported only after the original is stored
 * and the record is committed.
 */
public interface UploadDocument {

    /** {@code categoryId} optionally files the new document into a folder; null keeps it in Unsorted. */
    record UploadCommand(String originalFilename, String contentType, long declaredSize, InputStream content,
            List<SubjectView> subjects, UUID categoryId) {
        public UploadCommand(String originalFilename, String contentType, long declaredSize, InputStream content,
                List<SubjectView> subjects) {
            this(originalFilename, contentType, declaredSize, content, subjects, null);
        }
    }

    /** {@code duplicate} is true when the same content was already captured; the existing record is returned. */
    record UploadResult(DocumentSummary document, boolean duplicate) {
    }

    UploadResult upload(Actor actor, UploadCommand command);
}
