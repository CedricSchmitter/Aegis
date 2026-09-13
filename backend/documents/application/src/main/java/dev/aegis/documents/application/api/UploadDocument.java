package dev.aegis.documents.application.api;

import dev.aegis.shared.Actor;
import java.io.InputStream;
import java.util.List;

/** Durable PDF capture. Success is reported only after the original is stored and the record is committed. */
public interface UploadDocument {

    record UploadCommand(String originalFilename, String contentType, long declaredSize, InputStream content,
            List<SubjectView> subjects) {
    }

    /** {@code duplicate} is true when the same content was already captured; the existing record is returned. */
    record UploadResult(DocumentSummary document, boolean duplicate) {
    }

    UploadResult upload(Actor actor, UploadCommand command);
}
