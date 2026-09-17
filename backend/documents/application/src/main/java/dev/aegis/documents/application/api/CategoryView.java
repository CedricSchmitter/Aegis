package dev.aegis.documents.application.api;

import java.util.List;
import java.util.UUID;

/** A folder with its explicit mappings and the number of documents placed directly in it. */
public record CategoryView(UUID id, UUID parentId, String name, List<UUID> impliedLabelIds,
        List<SubjectView> impliedSubjects, long documentCount, long version) {
}
