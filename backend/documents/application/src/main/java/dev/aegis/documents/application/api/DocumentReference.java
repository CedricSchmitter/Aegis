package dev.aegis.documents.application.api;

import dev.aegis.shared.DocumentId;

/** Minimal public reference for other contexts (for example a task source). */
public record DocumentReference(DocumentId id, String title) {
}
