package dev.aegis.documents.application.api;

import dev.aegis.shared.DocumentId;

/**
 * Minimal public reference for other contexts (for example a task source). A trashed document still resolves, so a
 * link keeps its title and can say that its source is in the trash instead of pretending it vanished.
 */
public record DocumentReference(DocumentId id, String title, boolean trashed) {
}
