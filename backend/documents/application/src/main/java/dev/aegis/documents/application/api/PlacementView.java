package dev.aegis.documents.application.api;

import dev.aegis.shared.MemberId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Provenance of a document's folder placement and the placement a run proposed but did not apply (ADR-0005).
 * Confidence is the model's own estimate, not a calibrated probability.
 */
public final class PlacementView {
    private PlacementView() {
    }

    /**
     * Where the current placement came from. {@code origin} is {@code MANUAL}, {@code AUTOMATIC} or {@code null}
     * while the document has never been filed; {@code byMember} stays true once a member placed or moved it.
     * {@code revertible} marks an automatic move a member can take back.
     */
    public record Origin(String origin, Double confidence, String sourceRef, MemberId by, Instant at,
            boolean byMember, boolean revertible) {
        public static final Origin NONE = new Origin(null, null, null, null, null, false, false);
    }

    /**
     * A placement waiting for a member. {@code path} is the full readable path of the target; {@code newFolders}
     * are the folders at its end that do not exist yet and would be created on acceptance.
     * {@code impliedLabels} and {@code impliedSubjects} are what the target adds to the document, so the effect is
     * visible before accepting. {@code otherDocuments} counts further documents with the same pending target.
     */
    public record Suggestion(String key, UUID folderId, UUID anchorFolderId, Long anchorVersion, List<String> path,
            List<String> newFolders, Double confidence, String evidence, String runRef, String model,
            Instant createdAt, List<LabelChip> impliedLabels, List<SubjectView> impliedSubjects,
            int otherDocuments) {
        public Suggestion {
            path = List.copyOf(path);
            newFolders = List.copyOf(newFolders);
            impliedLabels = List.copyOf(impliedLabels);
            impliedSubjects = List.copyOf(impliedSubjects);
        }
    }
}
